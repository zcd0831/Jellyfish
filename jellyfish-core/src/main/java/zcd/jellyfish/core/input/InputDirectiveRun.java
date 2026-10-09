package zcd.jellyfish.core.input;

import zcd.jellyfish.api.extension.CancellationToken;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import zcd.jellyfish.infra.support.CancellationTokenSource;

/**
 * 一次输入指令执行的句柄：由 {@link InputDirectives#submit} 立即返回，执行在专用线程上推进。
 * <p>
 * <b>为什么需要它</b>：{@code !} 这类指令要跑工具（命令可能跑几分钟），而发起它的界面线程必须立刻返回
 * ——否则审批浮层弹不出来，{@code Esc} 也没人接。句柄因此提供三样东西：
 * <ul>
 *     <li>{@link #isDone()}：界面每帧轮询，用于在终结时收起「运行中」块；</li>
 *     <li>{@link #cancel()}：界面按 {@code Esc} 时触发，经 {@link CancellationToken} 送达工具
 *     （命令行靠它杀进程）；</li>
 *     <li>{@link #getCommand()}：回显原文，界面与落会话的消息都用它。</li>
 * </ul>
 * 不可变标识 + 线程安全的可取消状态：可安全跨线程传递。
 *
 * @author zcd
 */
public class InputDirectiveRun {

    /**
     * 「任务没被接受」时用的已完成句柄。
     * <p>
     * {@link #isDone()} 的语义是「已结束（成功、失败或取消）」，「执行器拒收」同样是结束——
     * 若它继续回答「还没跑完」，外壳就会永远停在「运行中」，而实际上什么都不会再发生。
     */
    private static final Future<?> DONE = CompletableFuture.completedFuture(null);

    /** 本次执行的标识，同时用作工具调用 id。 */
    private final String runId;

    /** 认领本次输入的标记字符。 */
    private final String marker;

    /** 用户输入原文（已修剪），用于回显。 */
    private final String command;

    /** 取消令牌本体：{@code Esc} 触发它，工具侧注册的回调据此杀进程。 */
    private final CancellationTokenSource cancellation = new CancellationTokenSource();

    /** 结束通知；由提交方在提交执行之前交进来，可为 {@code null}（外部手动组装的句柄没有提交方）。 */
    private final InputDirectiveCompletion completion;

    /** 异步任务句柄，由 {@link #submit} 注入。 */
    private volatile Future<?> future;

    /**
     * 构造执行句柄。
     * <p>
     * 公开构造器是因为它也是「外部手动组装」的接缝：外壳在测试里需要一个可观察的句柄，
     * 而不必惊动整个内核。运行时唯一的生产者是 {@link InputDirectives#start}。
     * <p>
     * <b>手动组装的句柄没有结束通知</b>：它不由内核提交执行，因此不会有「执行结束」这件事发生；
     * 需要那条通知的路径请走 {@link InputDirectives#submit}。
     *
     * @param runId   执行标识，不可为空白
     * @param marker  标记字符，不可为空白
     * @param command 用户输入原文（已修剪），可为 {@code null}（等价空串）
     */
    public InputDirectiveRun(String runId, String marker, String command) {
        this(runId, marker, command, null);
    }

    /**
     * 构造执行句柄，并带上结束通知。
     *
     * @param runId      执行标识，不可为空白
     * @param marker     标记字符，不可为空白
     * @param command    用户输入原文（已修剪），可为 {@code null}（等价空串）
     * @param completion 结束通知，可为 {@code null}（表示不需要通知）
     */
    InputDirectiveRun(String runId, String marker, String command, InputDirectiveCompletion completion) {
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.marker = Objects.requireNonNull(marker, "marker must not be null");
        this.command = command == null ? "" : command;
        this.completion = completion;
    }

    /**
     * 提交异步任务。
     * <p>
     * <b>任务没被接受时如实说「已结束」</b>：队列满时执行器直接拒绝，此后本句柄既不会跑、也不会有人
     * 来把它置成完成；若 {@link #isDone()} 继续回答「还没跑完」，外壳就会永远停在「运行中」。
     * 拒绝本身由调用方转成异常抛给外壳（见 {@link InputDirectives#start}）。
     *
     * @param executor 专用执行器，不可为 {@code null}
     * @param task     执行任务，不可为 {@code null}
     */
    void submit(ExecutorService executor, Runnable task) {
        try {
            this.future = executor.submit(task);
        } catch (RuntimeException e) {
            this.future = DONE;
            throw e;
        }
    }

    /**
     * 通知提交方「本次执行已结束」，恰好一次。
     * <p>
     * 由 {@link InputDirectives} 在执行的收尾处调用（与结果落库同一个 {@code finally}）；
     * 通知抛错由调用方兜住并记 WARN，不改写指令的结局。
     */
    void notifyFinished() {
        InputDirectiveCompletion callback = completion;
        if (callback != null) {
            callback.finished(this);
        }
    }

    /**
     * 取消本次执行，幂等。
     * <p>
     * 只发信号、不等待：调用点可能是界面渲染线程，阻塞等待会让界面卡住。工具（命令行）的终止链
     * 由它自己的等待循环接手。
     */
    public void cancel() {
        cancellation.cancel();
    }

    /**
     * 判断本次执行是否已被取消。
     *
     * @return 已取消返回 {@code true}
     */
    public boolean isCancelled() {
        return cancellation.isCancelled();
    }

    /**
     * 判断本次执行是否已结束。
     * <p>
     * 执行器拒收（队列满）时也返回 {@code true}：那一刻它已经不可能再跑了。
     *
     * @return 已结束（成功、失败、取消或未被受理）返回 {@code true}
     */
    public boolean isDone() {
        Future<?> current = future;
        return current != null && current.isDone();
    }

    /**
     * 获取执行标识。
     *
     * @return 执行标识，保证非 {@code null}
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取认领本次输入的标记字符。
     *
     * @return 标记字符，保证非 {@code null}
     */
    public String getMarker() {
        return marker;
    }

    /**
     * 获取用户输入原文（已修剪）。
     *
     * @return 输入原文，保证非 {@code null}
     */
    public String getCommand() {
        return command;
    }

    /**
     * 取取消令牌，供 {@link InputDirectives} 交给工具执行器。
     *
     * @return 取消令牌，保证非 {@code null}
     */
    CancellationToken token() {
        return cancellation;
    }

    @Override
    public String toString() {
        return "InputDirectiveRun{runId=" + runId + ", marker=" + marker + '}';
    }
}
