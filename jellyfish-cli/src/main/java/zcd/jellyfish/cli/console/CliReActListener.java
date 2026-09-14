package zcd.jellyfish.cli.console;

import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;

import java.util.Objects;

/**
 * {@link ReActListener} 的 CLI 渲染：把一次回合的回调翻译成「stdout 上的回答 + stderr 上的诊断」。
 * <p>
 * <b>分流契约（本类存在的理由）</b>：
 * <ul>
 *     <li><b>stdout</b>：回合最终回答——保证
 *     {@code jellyfish -cli -p "..." > answer.txt} 得到干净内容；</li>
 *     <li><b>stderr</b>：思考过程（可选）、中间轮次的模型文本、工具调用的开始与结束、回合失败、截断警告——
 *     诊断信息，它们对「回答本身」没有价值，但排查问题时必须可见。</li>
 * </ul>
 * <p>
 * <b>回答为什么先缓冲、收敛时再一次性落盘</b>：{@code ReActLooper} 把文本按增量回调，而工具进度、
 * 思考等诊断走 stderr。若在 {@link #onText(String)} 里就把半个句子写进 stdout，诊断行会紧贴在这半个句子后面，
 * 把回答从中间切断（终端里同一行会看到「回答半句→ read_file」）。因此这里把文本攒起来，
 * 只在 {@link #onComplete(ReActResult)} 时整体写 stdout：终端不再交错，文件内容仍是逐字节精确的回答。
 * <p>
 * <b>中间轮次的文本为什么归 stderr</b>：模型常在发起工具调用前先吐一句「我先看一下文件」，随后才给出最终回答。
 * 这段文本属于过程轨迹而非回答，实时写 stdout 会让同一句话在回答前后各出现一次。因此在
 * {@link #onToolCallStarted(String, String)} 处把它当作轨迹转写到 stderr（带 {@code … } 前缀），并清空缓冲，
 * 使 stdout 严格等于「本轮最终回答」。
 * <p>
 * <b>为什么只在与工具 / 思考交界处收尾思考行</b>：思考是逐块增量、不带换行地写到 stderr 的；
 * 若它还没结束就来了轨迹或工具行，两段内容会在终端里粘在同一行上。因此在「另一类输出开始」时补一个换行。
 *
 * @author zcd
 */
public final class CliReActListener implements ReActListener {

    /** 思考过程的行首标记。 */
    private static final String THINKING_PREFIX = "· ";

    /** 中间轮次模型文本的行首标记（过程轨迹，不是最终回答）。 */
    private static final String TRACE_PREFIX = "… ";

    /** 工具开始标记。 */
    private static final String TOOL_START_PREFIX = "→ ";

    /** 工具结束标记。 */
    private static final String TOOL_END_PREFIX = "← ";

    /** 输出面板。 */
    private final ConsoleIO console;

    /** 是否显示思考过程。 */
    private final boolean showThinking;

    /** 当前轮次已收到的文本缓冲，收敛时整体写 stdout；中间轮次则转写 stderr。 */
    private final StringBuilder answer = new StringBuilder();

    /** 思考行是否已经开始且未结束（未换行）。 */
    private boolean thinkingLineOpen;

    /** 本回合是否已经报过错，用于避免调用点重复打印同一条错误。 */
    private boolean failed;

    /**
     * 构造监听器。
     *
     * @param console      输出面板，不可为 {@code null}
     * @param showThinking 是否把思考过程打到 stderr
     */
    public CliReActListener(ConsoleIO console, boolean showThinking) {
        this.console = Objects.requireNonNull(console, "console must not be null");
        this.showThinking = showThinking;
    }

    @Override
    public void onText(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        closeThinkingLine();
        answer.append(delta);
    }

    @Override
    public void onThinking(String delta) {
        if (!showThinking || delta == null || delta.isEmpty()) {
            return;
        }
        if (!thinkingLineOpen) {
            // 行首标记只加在「思考行开始处」，增量块本身不加前缀，否则会每块一行
            console.writeErr(THINKING_PREFIX);
            thinkingLineOpen = true;
        }
        console.writeErr(delta);
        if (delta.endsWith("\n")) {
            thinkingLineOpen = false;
        }
    }

    @Override
    public void onToolCallStarted(String toolCallId, String toolName) {
        closeThinkingLine();
        // 走到这里说明本轮以工具调用收尾，缓冲的文本是过程轨迹而非最终回答，转写 stderr 后清空
        flushTrace();
        console.writeErrLine(TOOL_START_PREFIX + toolName);
    }

    @Override
    public void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output) {
        closeThinkingLine();
        console.writeErrLine(TOOL_END_PREFIX + toolName + (success ? " 完成" : " 失败")
                + "（" + lengthOf(output) + " 字符）");
    }

    @Override
    public void onComplete(ReActResult result) {
        closeThinkingLine();
        writeAnswer(result);
        if (result != null && result.isTruncated()) {
            console.writeErrLine("回合未收敛：已达最大轮次，上面的回答可能不完整。");
        }
    }

    @Override
    public void onCancelled() {
        closeThinkingLine();
        // 已取消的回合没有最终回答，残片留在缓冲里会丢，转写 stderr 让用户至少看得到已生成的部分
        flushTrace();
        console.writeErrLine("已取消。");
    }

    @Override
    public void onError(Throwable error) {
        closeThinkingLine();
        flushTrace();
        failed = true;
        console.writeErrLine("回合失败：" + messageOf(error));
    }

    /**
     * 判断本回合是否已经报过错。
     * <p>
     * 供调用点决定「要不要再打一次失败原因」：{@code ReActTurn.await()} 抛出时错误已经在上面的回调里打过，
     * 重复打印只会让终端里同一句话出现两遍。
     *
     * @return 已经报过错返回 {@code true}
     */
    public boolean isFailed() {
        return failed;
    }

    /**
     * 把缓冲的回答写进 stdout 并保证以换行收尾。
     * <p>
     * 缓冲为空时回退到 {@link ReActResult#getContent()}：截断回合的最后一轮文本已在工具行处作为轨迹输出，
     * 此时 stdout 需要承载结果里的可读提示，避免用户看到一片空白。
     *
     * @param result 回合结果，可为 {@code null}
     */
    private void writeAnswer(ReActResult result) {
        if (answer.length() > 0) {
            writeOutLine(answer.toString());
            answer.setLength(0);
            return;
        }
        String content = result == null ? null : result.getContent();
        if (content != null && !content.trim().isEmpty()) {
            writeOutLine(content);
        }
    }

    /**
     * 向 stdout 写出一段回答，未以换行结尾时补一个换行。
     *
     * @param text 回答文本，保证非空
     */
    private void writeOutLine(String text) {
        console.writeOut(text.endsWith("\n") ? text : text + "\n");
    }

    /**
     * 把缓冲中的中间轮次文本转写到 stderr，并清空缓冲。
     * <p>
     * 换行由内容自己带，未带时补一个，避免与后续诊断行粘在同一行。
     */
    private void flushTrace() {
        if (answer.length() == 0) {
            return;
        }
        console.writeErr(TRACE_PREFIX);
        console.writeErr(answer.toString());
        if (answer.charAt(answer.length() - 1) != '\n') {
            console.writeErr("\n");
        }
        answer.setLength(0);
    }

    /**
     * 结束进行中的思考行：补一个换行，避免与后续输出粘在同一行。
     */
    private void closeThinkingLine() {
        if (thinkingLineOpen) {
            console.writeErr("\n");
            thinkingLineOpen = false;
        }
    }

    /**
     * 取工具输出的字符长度。
     * <p>
     * 刻意只报长度不报内容：工具输出动辄上万字符，打进终端毫无价值，还会把回答挤出屏幕。
     *
     * @param output 工具输出，可为 {@code null}
     * @return 字符长度
     */
    private static int lengthOf(String output) {
        return output == null ? 0 : output.length();
    }

    /**
     * 取异常的可读描述。
     *
     * @param error 异常，可为 {@code null}
     * @return 错误描述，保证非空
     */
    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
