package zcd.jellyfish.tui;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;

import java.util.Objects;

/**
 * {@link ReActListener} 的 TUI 实现：把 {@code react} 线程上的 7 个回调，翻译成「往暂存区追加字节」。
 * <p>
 * <b>它是线程契约的落点</b>：除 {@link #onToolCallOutput(String, String, String)} 以外的回调都发生在
 * {@code react} 池线程，而界面状态只允许在渲染线程上变更。因此本类的全部职责就是「只做线程安全的最小动作」——
 * 写 {@link InflightTurn}，一行界面代码都不碰。界面在下一帧由渲染线程读 {@link InflightTurn#snapshot()} 得到。
 * <p>
 * <b>{@code onToolCallOutput} 为什么只能做「追加」</b>：它由工具的输出泵线程触发，stdout 与 stderr
 * 两条线程会并发进来。{@link InflightTurn#appendToolOutput(String)} 自己是同步的，因此这里不必再加锁；
 * 但绝不能在它里面做界面相关的事——那些东西只允许发生在渲染线程上。
 * <p>
 * <b>为什么不像 {@code CliReActListener} 那样处理「中间轮次文本」</b>：CLI 要把工具调用之前的模型文本
 * 转写到 stderr，是为了守住「stdout 严格等于最终回答」这条字节级契约。TUI 没有这条契约：
 * 那条 assistant 消息本身已经落进 {@code Session}（{@code ReActLooper} 先 {@code appendMessage}
 * 再执行工具），它会作为历史正常显示。因此这里<b>不需要</b> {@code flushTrace()} 式的转写逻辑——
 * 这是两套监听器唯一不能复用、也必须分成两个类的地方。
 * <p>
 * <b>为什么没有 {@code isFailed()}</b>：CLI 需要它来避免调用点重复打印同一条错误（终端里会看成两遍）。
 * TUI 的错误是消息区里的一行，调用点不需要再补一次。
 *
 * @author zcd
 */
public final class TuiReActListener implements ReActListener {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(TuiReActListener.class);

    /** 暂存区。 */
    private final InflightTurn inflight;

    /**
     * 构造监听器。
     *
     * @param inflight 暂存区，不可为 {@code null}
     */
    public TuiReActListener(InflightTurn inflight) {
        this.inflight = Objects.requireNonNull(inflight, "inflight must not be null");
    }

    @Override
    public void onText(String delta) {
        inflight.appendText(delta);
    }

    @Override
    public void onThinking(String delta) {
        inflight.appendThinking(delta);
    }

    @Override
    public void onToolCallStarted(String toolCallId, String toolName) {
        // 走到这里说明本轮模型响应已经落库，暂存区里的正文成了重复内容，必须清掉。
        // 清空晚于落库是安全的：ReActLooper 先 appendMessage 再回调。
        inflight.clearText();
        // 工具名先记下：一条只输出或根本不输出的命令，屏幕上也先得有个名字
        inflight.beginTool(toolName);
    }

    @Override
    public void onToolCallOutput(String toolCallId, String toolName, String chunk) {
        // 这条回调不在 react 线程上（工具的输出泵线程，stdout / stderr 各一条且会并发），
        // 因此这里只做一件线程安全的事：往暂存区追加。界面仍然只在渲染线程上变更。
        inflight.appendToolOutput(chunk);
    }

    @Override
    public void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output,
                                    Map<String, Object> metadata) {
        // 工具轨迹直接由会话消息投影得出（assistant.toolCalls 与 tool 消息都已落库，元数据也随之落库），
        // 此处无需记录任何东西——包括警告标记：它由投影读 SessionMessage 的元数据渲染，
        // 在这里再存一份只会多出「重启后标记消失」的不一致。
        // 实时输出要清掉：留到下一帧就是同一件事在屏幕上出现两份（一份实时、一份落库后）
        inflight.clearToolOutput();
    }

    @Override
    public void onComplete(ReActResult result) {
        inflight.clearText();
        inflight.clearToolOutput();
        boolean truncated = result != null && result.isTruncated();
        inflight.finish(truncated ? InflightTurn.Outcome.TRUNCATED : InflightTurn.Outcome.COMPLETED, null);
    }

    @Override
    public void onCancelled() {
        inflight.clearText();
        inflight.clearToolOutput();
        inflight.finish(InflightTurn.Outcome.CANCELLED, null);
    }

    @Override
    public void onError(Throwable error) {
        inflight.clearText();
        inflight.clearToolOutput();
        String message = messageOf(error);
        LOG.debug("TUI 回合失败：{}", message, error);
        inflight.finish(InflightTurn.Outcome.ERROR, message);
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
