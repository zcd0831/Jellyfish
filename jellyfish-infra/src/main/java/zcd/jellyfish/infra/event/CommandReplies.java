package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.command.Command;
import zcd.jellyfish.api.event.command.CommandException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

/**
 * 命令应答槽：按 {@link Command#getCommandId()} 保管在途命令的结果。
 * <p>
 * 应答槽从 api 的 {@link Command} 下沉到这里，是为了让插件只看到纯请求数据，无法误用「回填结果」这类框架内部能力；
 * 索引直接复用命令自带的 {@code commandId}，不额外引入映射层。
 * <p>
 * 生命周期由门面负责：{@link #open(Command)} 在派发前登记，{@link #close(Command)} 在派发结束的 {@code finally} 中回收，
 * 因此无论命令正常应答、处理失败、还是根本没有处理器，都只占用一次派发期间的短暂内存。
 *
 * @author zcd
 */
final class CommandReplies {

    /** 在途应答槽：命令标识 → 结果容器。 */
    private final ConcurrentMap<String, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();

    /**
     * 登记一条在途命令。
     *
     * @param command 命令对象
     */
    void open(Command<?> command) {
        pending.put(command.getCommandId(), new CompletableFuture<Object>());
    }

    /**
     * 回填命令结果。
     *
     * @param command 命令对象
     * @param result  结果对象，须满足 {@link Command#getResultType()}
     * @throws CommandException 结果类型不匹配时抛出
     */
    void complete(Command<?> command, Object result) {
        Class<?> resultType = command.getResultType();
        if (result != null && !resultType.isInstance(result)) {
            throw new CommandException(CommandException.Code.RESULT_TYPE_MISMATCH,
                    "expected " + resultType.getName() + " but got " + result.getClass().getName()
                            + " for command " + command.getCommandId());
        }
        CompletableFuture<Object> future = pending.get(command.getCommandId());
        if (future != null) {
            future.complete(result);
        }
    }

    /**
     * 回填命令失败。
     *
     * @param command 命令对象
     * @param cause   失败原因
     */
    void fail(Command<?> command, Throwable cause) {
        CompletableFuture<Object> future = pending.get(command.getCommandId());
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }

    /**
     * 取出命令结果。
     * <p>
     * 处理器抛出的运行时异常原样抛出；受检异常包装为 {@link JellyfishException}，因为该签名不应声明受检异常。
     *
     * @param command 命令对象
     * @param <R>     结果类型
     * @return 命令结果
     * @throws CommandException 未应答时抛出
     */
    @SuppressWarnings("unchecked")
    <R> R await(Command<R> command) {
        CompletableFuture<Object> future = pending.get(command.getCommandId());
        if (future == null) {
            throw new CommandException(CommandException.Code.NO_RESPONSE,
                    "reply slot missing: " + command.getCommandId());
        }
        if (!future.isDone()) {
            throw new CommandException(CommandException.Code.NO_RESPONSE,
                    "command not answered: " + command.getCommandId());
        }
        try {
            return (R) future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JellyfishException("interrupted while reading command result: " + command.getCommandId(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new JellyfishException("command handler failed: " + command.getCommandId(), cause);
        } catch (CancellationException e) {
            throw new CommandException(CommandException.Code.NO_RESPONSE,
                    "command cancelled: " + command.getCommandId(), e);
        }
    }

    /**
     * 回收应答槽。
     *
     * @param command 命令对象
     */
    void close(Command<?> command) {
        pending.remove(command.getCommandId());
    }
}
