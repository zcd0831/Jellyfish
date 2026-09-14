package zcd.jellyfish.cli.console;

import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;

import java.util.Objects;

/**
 * {@link ReActListener} 的 CLI 渲染：把一次回合的回调翻译成「stdout 上的回答 + stderr 上的诊断」。
 * <p>
 * <b>分流契约（本类存在的理由）</b>：
 * <ul>
 *     <li><b>stdout</b>：模型文本增量——回答与命令结果的唯一去向，保证
 *     {@code jellyfish -cli -p "..." > answer.txt} 得到干净内容；</li>
 *     <li><b>stderr</b>：思考过程（可选）、工具调用的开始与结束、回合失败、截断警告——诊断信息，
 *     它们对「回答本身」没有价值，但排查问题时必须可见。</li>
 * </ul>
 * <p>
 * <b>为什么不重复打印最终内容</b>：{@code ReActLooper} 已把每一段文本通过 {@link #onText(String)} 实时发出，
 * {@link ReActResult#getContent()} 携带的是同一份内容；在这里再打一次会出现「回答重复两遍」。
 * {@link #onComplete(ReActResult)} 只做两件事：收尾换行与截断警告。
 * <p>
 * <b>为什么只在与工具 / 回答交界处收尾思考行</b>：思考是逐块增量、不带换行地写到 stderr 的；
 * 若它还没结束就来了回答或工具行，两段内容会在终端里粘在同一行上。因此在「另一类输出开始」时补一个换行。
 *
 * @author zcd
 */
public final class CliReActListener implements ReActListener {

    /** 思考过程的行首标记。 */
    private static final String THINKING_PREFIX = "· ";

    /** 工具开始标记。 */
    private static final String TOOL_START_PREFIX = "→ ";

    /** 工具结束标记。 */
    private static final String TOOL_END_PREFIX = "← ";

    /** 输出面板。 */
    private final ConsoleIO console;

    /** 是否显示思考过程。 */
    private final boolean showThinking;

    /** 本回合是否已经输出过文本，用于决定收尾换行。 */
    private boolean textWritten;

    /** 最后一段文本是否以换行结尾。 */
    private boolean textEndsWithNewline = true;

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
        console.writeOut(delta);
        textWritten = true;
        textEndsWithNewline = delta.endsWith("\n");
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
        if (textWritten && !textEndsWithNewline) {
            console.writeOut("\n");
        }
        if (result != null && result.isTruncated()) {
            console.writeErrLine("回合未收敛：已达最大轮次，上面的回答可能不完整。");
        }
    }

    @Override
    public void onCancelled() {
        closeThinkingLine();
        console.writeErrLine("已取消。");
    }

    @Override
    public void onError(Throwable error) {
        closeThinkingLine();
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
