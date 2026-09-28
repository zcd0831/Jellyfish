package zcd.jellyfish.core.input;

import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.core.tool.CancellationTokenSource;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

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

    /** 本次执行的标识，同时用作工具调用 id。 */
    private final String runId;

    /** 认领本次输入的标记字符。 */
    private final String marker;

    /** 用户输入原文（已修剪），用于回显。 */
    private final String command;

    /** 取消令牌本体：{@code Esc} 触发它，工具侧注册的回调据此杀进程。 */
    private final CancellationTokenSource cancellation = new CancellationTokenSource();

    /** 异步任务句柄，由 {@link #submit} 注入。 */
    private volatile Future<?> future;

    /**
     * 构造执行句柄。
     * <p>
     * 公开构造器是因为它也是「外部手动组装」的接缝：外壳在测试里需要一个可观察的句柄，
     * 而不必惊动整个内核。运行时唯一的生产者是 {@link InputDirectives#start}。
     *
     * @param runId   执行标识，不可为空白
     * @param marker  标记字符，不可为空白
     * @param command 用户输入原文（已修剪），可为 {@code null}（等价空串）
     */
    public InputDirectiveRun(String runId, String marker, String command) {
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.marker = Objects.requireNonNull(marker, "marker must not be null");
        this.command = command == null ? "" : command;
    }

    /**
     * 提交异步任务。
     *
     * @param executor 专用执行器，不可为 {@code null}
     * @param task     执行任务，不可为 {@code null}
     */
    void submit(ExecutorService executor, Runnable task) {
        this.future = executor.submit(task);
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
     *
     * @return 已结束（成功、失败或取消）返回 {@code true}
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
