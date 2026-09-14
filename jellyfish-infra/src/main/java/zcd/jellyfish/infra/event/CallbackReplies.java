package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ExtensionException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 回调应答槽：按请求对象身份保管在途回调的结果。
 * <p>
 * 应答槽留在 infra 是为了让插件只看到纯请求数据，无法误用「回填结果」这类框架内部能力；
 * 请求对象不覆写 equals/hashCode，因此直接以实例作为索引键，不需要额外的标识字段。
 * <p>
 * 本机制属于过渡实现：同步派发改为「调用方持有 handler 并直接接收返回值」之后（方案 P4），
 * 应答槽会整体删除。
 * <p>
 * 多个处理器依次调用时会各回填一条结果，{@link #await(ExtensionRequest)} 取首条；
 * 失败由分发器在首个处理器失败时调用 {@link #fail(ExtensionRequest, Throwable)} 记录，读取时上抛。
 *
 * @author zcd
 */
final class CallbackReplies {

    /** 在途应答槽：请求对象 → 结果槽（请求不覆写 equals/hashCode，因此天然按实例身份索引）。 */
    private final ConcurrentMap<ExtensionRequest<?>, Slot> pending = new ConcurrentHashMap<>();

    /**
     * 登记一条在途回调。
     *
     * @param callback 回调对象
     */
    void open(ExtensionRequest<?> callback) {
        pending.put(callback, new Slot());
    }

    /**
     * 回填一条结果。
     *
     * @param callback 回调对象
     * @param result   结果对象，须满足 {@link ExtensionRequest#getResultType()}
     * @throws ExtensionException 结果类型不匹配时抛出
     */
    void complete(ExtensionRequest<?> callback, Object result) {
        Class<?> resultType = callback.getResultType();
        if (result != null && !resultType.isInstance(result)) {
            throw new ExtensionException(ExtensionException.Code.RESULT_TYPE_MISMATCH,
                    "expected " + resultType.getName() + " but got " + result.getClass().getName()
                            + " for " + callback);
        }
        Slot slot = pending.get(callback);
        if (slot != null) {
            slot.add(result);
        }
    }

    /**
     * 回填回调失败。
     *
     * @param callback 回调对象
     * @param cause    失败原因
     */
    void fail(ExtensionRequest<?> callback, Throwable cause) {
        Slot slot = pending.get(callback);
        if (slot != null) {
            slot.fail(cause);
        }
    }

    /**
     * 取出回调结果。
     * <p>
     * 处理器抛出的运行时异常原样抛出；受检异常包装为 {@link JellyfishException}，因为该签名不应声明受检异常。
     * 多个处理器依次调用时此处返回首个结果。
     *
     * @param callback 回调对象
     * @param <R>      结果类型
     * @return 回调结果
     * @throws ExtensionException 未应答时抛出
     */
    @SuppressWarnings("unchecked")
    <R> R await(ExtensionRequest<R> callback) {
        Slot slot = requireSlot(callback);
        return (R) slot.single(callback);
    }

    /**
     * 回收应答槽。
     *
     * @param callback 回调对象
     */
    void close(ExtensionRequest<?> callback) {
        pending.remove(callback);
    }

    /**
     * 获取回调对应的结果槽。
     *
     * @param callback 回调对象
     * @return 结果槽
     * @throws ExtensionException 槽已回收或从未登记时抛出
     */
    private Slot requireSlot(ExtensionRequest<?> callback) {
        Slot slot = pending.get(callback);
        if (slot == null) {
            throw new JellyfishException("reply slot missing for " + callback);
        }
        return slot;
    }

    /**
     * 单次回调的结果槽：收集结果并记录首个失败。
     *
     * @author zcd
     */
    private static final class Slot {

        /** 已成功的结果，按回填顺序排列。 */
        private final List<Object> results = new ArrayList<>();

        /** 首个失败原因，未失败时为 {@code null}。 */
        private Throwable failure;

        /**
         * 追加一条成功结果。
         *
         * @param result 结果对象，可为 {@code null}
         */
        synchronized void add(Object result) {
            results.add(result);
        }

        /**
         * 记录失败，仅保留首个失败原因。
         *
         * @param cause 失败原因
         */
        synchronized void fail(Throwable cause) {
            if (failure == null) {
                failure = cause;
            }
        }

        /**
         * 读取结果。
         *
         * @param callback 回调对象，用于异常信息
         * @return 首个结果
         * @throws ExtensionException 未应答时抛出
         */
        synchronized Object single(ExtensionRequest<?> callback) {
            if (failure != null) {
                throw rethrow(failure);
            }
            if (results.isEmpty()) {
                throw new JellyfishException("callback not answered: " + callback);
            }
            return results.get(0);
        }

        /**
         * 把失败原因还原为可抛出的异常：运行时异常与 Error 原样抛出，受检异常包装。
         *
         * @param cause 失败原因
         * @return 可抛出的运行时异常
         */
        private static RuntimeException rethrow(Throwable cause) {
            if (cause instanceof RuntimeException) {
                return (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            return new JellyfishException("callback handler failed", cause);
        }
    }
}
