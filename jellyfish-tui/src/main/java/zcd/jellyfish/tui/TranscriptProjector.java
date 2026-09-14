package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.LineWrapper;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 投影器：把「会话历史 + 进行中回合」渲染成终端视觉行序列。
 * <p>
 * <b>这是一个纯函数</b>：输入是（消息列表、暂存区快照、可用列数、投影上限），输出是视觉行列表，
 * 不读时钟、不碰终端、不改任何状态。因此它的全部行为都能在单测里断言，包括中文换行、
 * 角色层级、工具轨迹折叠这些最容易出错的部分。
 * <p>
 * <b>为什么「视图 = 会话投影」的落脚点在这里</b>：屏幕上出现什么，完全由本类的输出决定；
 * 滚动状态（{@link ChatState}）只决定「看输出里的哪一段」，不参与「输出里有什么」。
 * 两者职责分开之后，插件写入历史、命令改写会话这些外部变化都会自动反映到屏幕上——
 * 因为屏幕不是历史的副本，而是历史的一次投影。
 * <p>
 * <b>视觉语法（§2.3）</b>：层级完全由「前缀 + 缩进」表达，不用气泡也不画框。
 * <pre>
 *   ❯ 用户消息
 *
 *   ⏺ jellyfish
 *     助手正文
 *       ⎿ 工具轨迹（暗色）
 *       ✻ 思考过程（暗色斜体）
 *       ⎿ 已中断（黄）/ 错误（红）
 * </pre>
 * 相邻的 assistant 与 tool 消息<b>共用一个</b> {@code ⏺ jellyfish} 表头：{@code ReActLooper}
 * 每轮都会落一条 assistant 消息，逐条画表头会在多轮工具调用时刷出一片重复的标题。
 *
 * @author zcd
 */
public final class TranscriptProjector {

    /** 用户消息前缀。 */
    static final String USER_PREFIX = "  \u276f ";

    /** 助手消息表头。 */
    static final String ASSISTANT_HEADER = "  \u23fa jellyfish";

    /** 助手正文缩进。 */
    static final String BODY_INDENT = "    ";

    /** 工具轨迹前缀。 */
    static final String TRACE_PREFIX = "      \u23bf ";

    /** 思考过程前缀。 */
    static final String THINKING_PREFIX = "      \u273b ";

    /** 提示行前缀（中断 / 错误 / 截断）。 */
    static final String NOTICE_PREFIX = "      \u23bf ";

    /** 默认投影的消息条数上限（T8.8）。每帧投影是 O(总行数)，必须有界。 */
    public static final int DEFAULT_MAX_MESSAGES = 500;

    /** 用户消息正文样式。 */
    private static final Style USER_STYLE = Style.EMPTY;

    /** 用户消息前缀样式。 */
    private static final Style USER_PREFIX_STYLE = Style.EMPTY.cyan().bold();

    /** 助手表头样式。 */
    private static final Style ASSISTANT_HEADER_STYLE = Style.EMPTY.green();

    /** 正文样式。 */
    private static final Style BODY_STYLE = Style.EMPTY;

    /** 工具轨迹样式。 */
    private static final Style TRACE_STYLE = Style.EMPTY.dim();

    /** 思考过程样式。 */
    private static final Style THINKING_STYLE = Style.EMPTY.dim().italic();

    /** 中断提示样式。 */
    private static final Style CANCELLED_STYLE = Style.EMPTY.yellow();

    /** 错误提示样式。 */
    private static final Style ERROR_STYLE = Style.EMPTY.red();

    /** 折叠提示样式。 */
    private static final Style FOLDED_STYLE = Style.EMPTY.dim();

    /** 处理中提示样式。 */
    private static final Style PENDING_STYLE = Style.EMPTY.dim();

    private TranscriptProjector() {
    }

    /**
     * 投影出完整视觉行序列。
     *
     * @param messages    会话消息列表，可为 {@code null}（当作空）
     * @param inflight    进行中回合快照，不可为 {@code null}
     * @param width       可用列数，小于 1 时按 1 处理
     * @param maxMessages 参与投影的最近消息条数上限；小于 1 时使用 {@link #DEFAULT_MAX_MESSAGES}
     * @return 视觉行列表，保证非 {@code null}
     */
    public static List<VisualLine> project(List<SessionMessage> messages, InflightTurn.Snapshot inflight,
                                           int width, int maxMessages) {
        List<SessionMessage> source = messages == null ? Collections.<SessionMessage>emptyList() : messages;
        int limit = maxMessages < 1 ? DEFAULT_MAX_MESSAGES : maxMessages;
        int start = Math.max(0, source.size() - limit);
        int folded = start;

        List<VisualLine> out = new ArrayList<VisualLine>();
        if (folded > 0) {
            // 用与其它提示行相同的前缀，保持左侧缩进一致；用裸空前缀会让这行顶到最左边
            out.addAll(LineWrapper.wrap(new StyledSegment(NOTICE_PREFIX, FOLDED_STYLE),
                    folded + " 条更早的消息已折叠", width));
        }

        boolean insideAssistantBlock = false;
        for (int i = start; i < source.size(); i++) {
            SessionMessage message = source.get(i);
            String role = message.getRole();
            String content = contentOf(message);
            if (LlmMessage.ROLE_USER.equals(role)) {
                appendUser(out, content, width);
                insideAssistantBlock = false;
            } else if (LlmMessage.ROLE_ASSISTANT.equals(role)) {
                insideAssistantBlock = appendAssistant(out, insideAssistantBlock, content, width);
            } else if (LlmMessage.ROLE_TOOL.equals(role)) {
                insideAssistantBlock = appendToolTrace(out, insideAssistantBlock, message, width);
            }
            // 其余角色（如 system）不进消息列表；即便进了也不显示，避免泄漏系统提示词
        }
        appendInflight(out, inflight, width);
        return out;
    }

    /**
     * 构造一条外壳提示行（命令结果 / 状态反馈）。
     * <p>
     * <b>为什么提示行不进会话</b>：命令的副作用写回各自的域服务，「命令输出文本」不是会话消息。
     * 把它塞进会话会污染发给模型的历史（模型会以为自己说过 {@code /help} 的返回值）。
     * 因此它由外壳持有、附在投影之后，生命周期与界面一致而与会话无关。
     *
     * @param text  提示文本，不可为 {@code null}；可含 {@code '\n'}
     * @param error 是否为错误（决定颜色）
     * @param width 可用列数
     * @return 视觉行列表，保证非 {@code null}
     */
    public static List<VisualLine> notice(String text, boolean error, int width) {
        Style style = error ? ERROR_STYLE : TRACE_STYLE;
        List<VisualLine> out = new ArrayList<VisualLine>();
        String[] rawLines = text.split("\n", -1);
        for (String rawLine : rawLines) {
            out.addAll(LineWrapper.wrap(new StyledSegment(TRACE_PREFIX, style),
                    Collections.singletonList(new StyledSegment(rawLine, style)), width));
        }
        return out;
    }

    /**
     * 投影一条用户消息。
     *
     * @param out   输出列表
     * @param body  正文
     * @param width 可用列数
     */
    private static void appendUser(List<VisualLine> out, String body, int width) {
        out.add(VisualLine.EMPTY);
        out.addAll(LineWrapper.wrap(new StyledSegment(USER_PREFIX, USER_PREFIX_STYLE),
                wrapBody(body, USER_STYLE), width));
    }

    /**
     * 投影一条助手消息。
     *
     * @param out          输出列表
     * @param inBlock      当前是否已处于助手块内
     * @param content      正文
     * @param width        可用列数
     * @return 投影后是否处于助手块内
     */
    private static boolean appendAssistant(List<VisualLine> out, boolean inBlock, String content, int width) {
        boolean blank = isBlank(content);
        if (blank) {
            // 只带工具调用的轮次没有正文，此处不落任何行；表头由随后的 tool 轨迹补上
            return inBlock;
        }
        if (!inBlock) {
            out.add(VisualLine.EMPTY);
            out.add(VisualLine.of(new StyledSegment(ASSISTANT_HEADER, ASSISTANT_HEADER_STYLE)));
        }
        out.addAll(LineWrapper.wrap(new StyledSegment(BODY_INDENT, Style.EMPTY),
                wrapBody(content, BODY_STYLE), width));
        return true;
    }

    /**
     * 投影一条工具结果消息为轨迹行。
     *
     * @param out      输出列表
     * @param inBlock  当前是否已处于助手块内
     * @param message  工具消息
     * @param width    可用列数
     * @return 投影后是否处于助手块内（恒为 {@code true}）
     */
    private static boolean appendToolTrace(List<VisualLine> out, boolean inBlock,
                                           SessionMessage message, int width) {
        if (!inBlock) {
            out.add(VisualLine.EMPTY);
            out.add(VisualLine.of(new StyledSegment(ASSISTANT_HEADER, ASSISTANT_HEADER_STYLE)));
        }
        String name = message.getMessage().getName();
        String label = name == null || name.isEmpty() ? "工具" : name;
        out.addAll(LineWrapper.wrap(new StyledSegment(TRACE_PREFIX, TRACE_STYLE),
                wrapBody(label, TRACE_STYLE), width));
        return true;
    }

    /**
     * 投影进行中回合。
     *
     * @param out      输出列表
     * @param inflight 暂存区快照
     * @param width    可用列数
     */
    private static void appendInflight(List<VisualLine> out, InflightTurn.Snapshot inflight, int width) {
        String thinking = inflight.getThinking();
        String text = inflight.getText();
        InflightTurn.Outcome outcome = inflight.getOutcome();

        if (outcome == InflightTurn.Outcome.RUNNING) {
            boolean hasBody = !isBlank(text) || !isBlank(thinking);
            if (!hasBody) {
                // 没有可显示增量时给一个「还在干活」的信号，否则屏幕看起来像卡死了
                out.add(VisualLine.of(new StyledSegment(NOTICE_PREFIX + "处理中\u2026", PENDING_STYLE)));
                return;
            }
            out.add(VisualLine.EMPTY);
            out.add(VisualLine.of(new StyledSegment(ASSISTANT_HEADER, ASSISTANT_HEADER_STYLE)));
            if (!isBlank(thinking)) {
                out.addAll(LineWrapper.wrap(new StyledSegment(THINKING_PREFIX, THINKING_STYLE),
                        wrapBody(thinking, THINKING_STYLE), width));
            }
            if (!isBlank(text)) {
                out.addAll(LineWrapper.wrap(new StyledSegment(BODY_INDENT, Style.EMPTY),
                        wrapBody(text, BODY_STYLE), width));
            }
            if (inflight.isTextTruncated()) {
                out.add(VisualLine.of(new StyledSegment(NOTICE_PREFIX + "内容过长，仅显示末尾", FOLDED_STYLE)));
            }
            return;
        }
        appendOutcomeNotice(out, inflight, width);
    }

    /**
     * 投影回合终局提示。
     *
     * @param out      输出列表
     * @param inflight 暂存区快照
     * @param width    可用列数
     */
    private static void appendOutcomeNotice(List<VisualLine> out, InflightTurn.Snapshot inflight, int width) {
        switch (inflight.getOutcome()) {
            case CANCELLED:
                out.add(VisualLine.of(new StyledSegment(NOTICE_PREFIX + "已中断", CANCELLED_STYLE)));
                break;
            case TRUNCATED:
                out.add(VisualLine.of(new StyledSegment(NOTICE_PREFIX + "回合未收敛：已达最大轮次", CANCELLED_STYLE)));
                break;
            case ERROR:
                String reason = inflight.getErrorMessage();
                String label = reason == null || reason.isEmpty() ? "回合失败" : "回合失败：" + reason;
                out.addAll(LineWrapper.wrap(new StyledSegment(NOTICE_PREFIX, ERROR_STYLE),
                        wrapBody(label, ERROR_STYLE), width));
                break;
            default:
                // COMPLETED / RUNNING：正文已由会话消息承载，这里不补任何行
                break;
        }
    }

    /**
     * 把一段正文转成样式段列表。
     *
     * @param content 正文，可为 {@code null}
     * @param style   样式
     * @return 样式段列表，正文为空时返回空列表
     */
    private static List<StyledSegment> wrapBody(String content, Style style) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new StyledSegment(content, style));
    }

    /**
     * 构造带样式的空白段。
     *
     * @param style 样式
     * @return 空文本样式段
     */
    private static StyledSegment plain(Style style) {
        return new StyledSegment("", style);
    }

    /**
     * 取出消息正文。
     *
     * @param message 会话消息
     * @return 正文，保证非 {@code null}
     */
    private static String contentOf(SessionMessage message) {
        String content = message.getMessage().getContent();
        return content == null ? "" : content;
    }

    /**
     * 判断文本是否为空白。
     *
     * @param text 文本，可为 {@code null}
     * @return 空白返回 {@code true}
     */
    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
