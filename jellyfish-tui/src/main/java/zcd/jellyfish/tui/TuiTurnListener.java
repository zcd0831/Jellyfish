package zcd.jellyfish.tui;

import java.util.Objects;

import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.ShellTurnListener;

/**
 * {@link ShellTurnListener} 的 TUI 实现：把可靠 lane 上的事件翻译成「往暂存区追加字节」。
 * <p>
 * <b>它是线程契约的落点</b>：事件在内核的发布线程上到达（{@code react} / {@code llm-stream}，
 * 以及工具的 stdout / stderr 泵线程），而界面状态只允许在渲染线程上变更。因此本类的全部职责就是
 * 「只做线程安全的最小动作」——写 {@link InflightTurn}，一行界面代码都不碰。
 * 界面在下一帧由渲染线程读 {@link InflightTurn#snapshot()} 得到。
 * <p>
 * <b>{@code TOOL_OUTPUT} 为什么只能做「追加」</b>：它由工具的输出泵线程触发，stdout 与 stderr
 * 两条线程会并发进来。{@link InflightTurn#appendToolOutput(String)} 自己是同步的，因此这里不必再加锁；
 * 但绝不能在它里面做界面相关的事——那些东西只允许发生在渲染线程上。
 * <p>
 * <b>为什么不像 CLI 那样处理「中间轮次文本」</b>：CLI 要把工具调用之前的模型文本转写到 stderr，
 * 是为了守住「stdout 严格等于最终回答」这条字节级契约。TUI 没有这条契约：
 * 那条 assistant 消息本身已经落进 {@code Session}（内核先 {@code appendMessage} 再执行工具），
 * 它会作为历史正常显示。因此这里<b>不需要</b>转写逻辑——这是两套订阅者唯一不能复用、
 * 也必须分成两个类的地方。
 * <p>
 * <b>为什么没有终态闩锁</b>：CLI 需要阻塞等结果才能决定退出码；TUI 是事件驱动的，
 * 终态到达就收暂存区，调用点不需要再补一次。
 *
 * @author zcd
 */
public final class TuiTurnListener implements ShellTurnListener {

    /** 暂存区。 */
    private final InflightTurn inflight;

    /**
     * 构造监听器。
     *
     * @param inflight 暂存区，不可为 {@code null}
     */
    public TuiTurnListener(InflightTurn inflight) {
        this.inflight = Objects.requireNonNull(inflight, "inflight must not be null");
    }

    @Override
    public void onTurnEvent(ShellTurnEvent event) {
        switch (event.getKind()) {
            case STARTED:
                // 暂存区在提交之前就已重置（beginWork），这里没有可做的事
                break;
            case TEXT:
                inflight.appendText(event.getText());
                break;
            case THINKING:
                inflight.appendThinking(event.getText());
                break;
            case TOOL_STARTED:
                // 走到这里说明本轮模型响应已经落库，暂存区里的正文成了重复内容，必须清掉。
                // 清空晚于落库是安全的：内核先 appendMessage 再回调
                inflight.clearText();
                // 工具名与参数先记下：一条只输出或根本不输出的命令，屏幕上也先得有个名字与目标
                inflight.beginTool(event.getToolName(), event.getToolArguments());
                break;
            case TOOL_OUTPUT:
                // 这条不在 react 线程上（工具的输出泵线程，stdout / stderr 各一条且会并发），
                // 因此这里只做一件线程安全的事：往暂存区追加。界面仍然只在渲染线程上变更。
                inflight.appendToolOutput(event.getText());
                break;
            case TOOL_COMPLETED:
                // 工具轨迹直接由会话消息投影得出（assistant.toolCalls 与 tool 消息都已落库，元数据也随之落库），
                // 此处无需记录任何东西——包括警告标记：它由投影读 SessionMessage 的元数据渲染，
                // 在这里再存一份只会多出「重启后标记消失」的不一致。
                // 实时输出要清掉：留到下一帧就是同一件事在屏幕上出现两份（一份实时、一份落库后）
                inflight.clearToolOutput();
                break;
            case COMPLETED:
                inflight.clearText();
                inflight.clearToolOutput();
                // 提示（被截断 / 空回复）跟着终局一起交给渲染层：它不是错误，但必须让用户看到
                inflight.finish(event.isTruncated() ? InflightTurn.Outcome.TRUNCATED
                        : InflightTurn.Outcome.COMPLETED, event.getNotice());
                break;
            case CANCELLED:
                inflight.clearText();
                inflight.clearToolOutput();
                inflight.finish(InflightTurn.Outcome.CANCELLED, null);
                break;
            case BLOCKED:
                // 理由走与失败同一条展示通道（终局行），但用中性样式：不是错误，是策略拦截。
                // 它也没必要清空增量缓冲——一句都没发给模型，缓冲里不可能有东西。
                inflight.finish(InflightTurn.Outcome.BLOCKED, event.getReason());
                break;
            case ERROR:
                inflight.clearText();
                inflight.clearToolOutput();
                inflight.finish(InflightTurn.Outcome.ERROR, messageOf(event.getError()));
                break;
            default:
                break;
        }
    }

    /**
     * 取异常的可读描述。
     *
     * @param error 异常，可为 {@code null}
     * @return 错误描述，保证非 {@code null}
     */
    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
