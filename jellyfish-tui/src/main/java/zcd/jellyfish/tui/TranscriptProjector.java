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
 * <b>这是一个纯函数</b>：输入是（消息列表、外壳提示列表、暂存区快照、可用列数、投影上限），输出是视觉行列表，
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

    /** 命令回显前缀：命令是用户敲的，回显成一行用户消息（与 {@link #USER_PREFIX} 同形）最易读。 */
    static final String SHELL_COMMAND_PREFIX = USER_PREFIX;

    /** 外壳提示块的正文前缀（6 列）：只在块首行出现，续行用 {@link #SHELL_INDENT}。 */
    static final String SHELL_PREFIX = "    \u23bf ";

    /** 外壳提示块的警告前缀（未知命令等，6 列）。 */
    static final String SHELL_WARN_PREFIX = "    ! ";

    /** 外壳提示块的失败前缀（6 列）。 */
    static final String SHELL_ERROR_PREFIX = "    \u2717 ";

    /** 外壳提示块的续行缩进，与三种块前缀等宽（6 列），保证显式换行与自动换行的缩进一致。 */
    static final String SHELL_INDENT = "      ";

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

    /**
     * 外壳提示正文样式。
     * <p>
     * 刻意<b>不用</b> {@link #TRACE_STYLE}：暗色是为「不想细读的工具轨迹」设的，
     * 而命令输出是用户主动要的结果（{@code /help} 的列表、{@code /status} 的当前态），应当与正文同权重。
     */
    private static final Style SHELL_STYLE = Style.EMPTY;

    /** 外壳提示的警告样式（未知命令：不是失败，但要看得见）。 */
    private static final Style WARN_STYLE = Style.EMPTY.yellow();

    private TranscriptProjector() {
    }

    /**
     * 首页投影：没有当前会话时消息区显示的内容。
     * <p>
     * 只有字标与外壳提示两类：首页上还没有会话，自然没有消息可投影，也没有「进行中回合」。
     * 保留外壳提示是因为首页上仍可能发生「命令报错」这类反馈（例如 {@code /resume} 指向不存在的会话、
     * {@code /delete} 删不掉），它必须在首页上看得见，否则用户会以为按键没生效。
     *
     * @param notices 外壳提示列表（按插入顺序，时间戳非递减），可为 {@code null}（当作空）
     * @param width   可用列数，小于 1 时按 1 处理
     * @return 视觉行列表，保证非 {@code null}
     */
    public static List<VisualLine> home(List<ShellNotice> notices, int width) {
        List<VisualLine> out = new ArrayList<VisualLine>();
        out.addAll(HomeSplash.lines(width));
        if (notices != null) {
            for (ShellNotice notice : notices) {
                out.addAll(notice(notice, width));
            }
        }
        return out;
    }

    /**
     * 投影出完整视觉行序列。
     * <p>
     * <b>外壳提示按时间戳归并进消息流</b>：命令输出是外壳状态而不是会话消息（见 {@link ShellNotice}），
     * 但它发生在一个确定的时刻上，因此它应当出现在那个时刻对应的消息之间。此前「投影完再整体拼接」的做法
     * 会让它永远贴在屏幕最底部，后发生的对话反而显示在它上面。
     * <p>
     * 归并规则：提示插在「第一条时间戳严格大于它的消息」之前（同毫秒时消息在前，提示排在触发它的那条之后）。
     * <b>比投影窗口更旧的提示直接丢弃</b>：它们的位置在「已折叠」行之前，显示出来只会错位。
     *
     * @param messages    会话消息列表，可为 {@code null}（当作空）
     * @param notices     外壳提示列表（按插入顺序，时间戳非递减），可为 {@code null}（当作空）
     * @param inflight    进行中回合快照，不可为 {@code null}
     * @param width       可用列数，小于 1 时按 1 处理
     * @param maxMessages 参与投影的最近消息条数上限；小于 1 时使用 {@link #DEFAULT_MAX_MESSAGES}
     * @return 视觉行列表，保证非 {@code null}
     */
    public static List<VisualLine> project(List<SessionMessage> messages, List<ShellNotice> notices,
                                           InflightTurn.Snapshot inflight, int width, int maxMessages) {
        List<SessionMessage> source = messages == null ? Collections.<SessionMessage>emptyList() : messages;
        List<ShellNotice> noticeSource = notices == null ? Collections.<ShellNotice>emptyList() : notices;
        int limit = maxMessages < 1 ? DEFAULT_MAX_MESSAGES : maxMessages;
        int start = Math.max(0, source.size() - limit);
        int folded = start;

        List<VisualLine> out = new ArrayList<VisualLine>();
        // 普通投影没有前缀消息，因此初始不在助手块内；首条 assistant / tool 消息会自己补表头
        boolean insideAssistantBlock = false;
        long noticeCutoff = Long.MIN_VALUE;
        if (folded > 0) {
            // 用与其它提示行相同的前缀，保持左侧缩进一致；用裸空前缀会让这行顶到最左边
            out.addAll(LineWrapper.wrap(new StyledSegment(NOTICE_PREFIX, FOLDED_STYLE),
                    folded + " 条更早的消息已折叠", width));
            noticeCutoff = source.get(start).getTimestamp();
        }

        int noticeIndex = firstVisibleNotice(noticeSource, noticeCutoff);
        for (int i = start; i < source.size(); i++) {
            SessionMessage message = source.get(i);
            noticeIndex = appendNoticesBefore(out, noticeSource, noticeIndex, message.getTimestamp(), width);
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
        // 时间戳比最后一条消息还晚的提示（含同毫秒的）落在会话之后
        while (noticeIndex < noticeSource.size()) {
            out.addAll(notice(noticeSource.get(noticeIndex), width));
            noticeIndex++;
        }
        appendInflight(out, inflight, width);
        return out;
    }

    /**
     * 找出第一条不在投影窗口之前的提示。
     *
     * @param notices 提示列表（按插入顺序）
     * @param cutoff  投影窗口最早一条消息的时间戳；没有折叠时传 {@link Long#MIN_VALUE}
     * @return 第一条可见提示的下标
     */
    private static int firstVisibleNotice(List<ShellNotice> notices, long cutoff) {
        int index = 0;
        while (index < notices.size() && notices.get(index).getTimestamp() < cutoff) {
            index++;
        }
        return index;
    }

    /**
     * 投影所有时间戳严格早于给定消息的提示。
     * <p>
     * 用严格小于而不是小于等于：时间戳相同时消息在前，因为提示是「对刚发生的事的反馈」。
     *
     * @param out       输出列表
     * @param notices   提示列表（按插入顺序）
     * @param from      起始下标
     * @param timestamp 消息时间戳
     * @param width     可用列数
     * @return 未投影的第一条提示的下标
     */
    private static int appendNoticesBefore(List<VisualLine> out, List<ShellNotice> notices,
                                           int from, long timestamp, int width) {
        int index = from;
        while (index < notices.size() && notices.get(index).getTimestamp() < timestamp) {
            out.addAll(notice(notices.get(index), width));
            index++;
        }
        return index;
    }

    /**
     * 投影一条外壳提示（命令结果 / 状态反馈）。
     * <p>
     * <b>为什么提示行不进会话</b>：命令的副作用写回各自的域服务，「命令输出文本」不是会话消息。
     * 把它塞进会话会污染发给模型的历史（模型会以为自己说过 {@code /help} 的返回值）。
     * 因此它由外壳持有，进入投影时由 {@link #project} 按时间戳排到正确位置。
     * <p>
     * <b>块的形态</b>（§2.3）：
     * <pre>
     *   ❯ /help                          ← 命令原文回显（用户消息同形）
     *     ⎿ 可用命令（2 条）：              ← 块首行带前缀
     *         /agent  切换 agent          ← 续行 6 列悬挂缩进 + 原始缩进
     *         /help   查看帮助
     * </pre>
     * 三条渲染规则各有原因：
     * <ul>
     *     <li><b>只首行带前缀</b>：逐行重复 {@code ⎿} 会把多行输出变成一摧箭头，左边界被吃掉一大截；</li>
     *     <li><b>续行悬挂缩进而不是继续缩进</b>：提示是一个整体，逐层缩进会让它看起来属于上一条助手消息；</li>
     *     <li><b>原始行首空格并入前缀</b>：{@code LineWrapper} 会丢弃正文行首空格，
     *     而 {@code /help} 这类输出靠它表达列表层级——丢了就和标题平齐了。</li>
     * </ul>
     *
     * @param notice 外壳提示，不可为 {@code null}
     * @param width  可用列数
     * @return 视觉行列表，保证非 {@code null}（块首有一个空行）
     */
    public static List<VisualLine> notice(ShellNotice notice, int width) {
        List<VisualLine> out = new ArrayList<VisualLine>();
        // 块前空行：多条命令连续执行时，没有它就糊成一片，看不出上一块输出到哪里结束
        out.add(VisualLine.EMPTY);
        String command = notice.getCommand();
        if (command != null && !command.trim().isEmpty()) {
            out.addAll(LineWrapper.wrap(new StyledSegment(SHELL_COMMAND_PREFIX, USER_PREFIX_STYLE),
                    wrapBody(command, USER_STYLE), width));
        }
        String marker = markerOf(notice.getKind());
        Style style = styleOf(notice.getKind());
        boolean first = true;
        for (String rawLine : notice.getText().split("\n", -1)) {
            String lead = leadingSpaces(rawLine);
            String prefix = (first ? marker : SHELL_INDENT) + lead;
            out.addAll(LineWrapper.wrap(new StyledSegment(prefix, style),
                    wrapBody(rawLine.substring(lead.length()), style), width));
            first = false;
        }
        return out;
    }

    /**
     * 取一种提示语义对应的块前缀。
     *
     * @param kind 提示语义
     * @return 块前缀（三种等宽，均为 6 列）
     */
    private static String markerOf(ShellNotice.Kind kind) {
        switch (kind) {
            case WARN:
                return SHELL_WARN_PREFIX;
            case ERROR:
                return SHELL_ERROR_PREFIX;
            default:
                return SHELL_PREFIX;
        }
    }

    /**
     * 取一种提示语义对应的样式。
     *
     * @param kind 提示语义
     * @return 样式
     */
    private static Style styleOf(ShellNotice.Kind kind) {
        switch (kind) {
            case WARN:
                return WARN_STYLE;
            case ERROR:
                return ERROR_STYLE;
            default:
                return SHELL_STYLE;
        }
    }

    /**
     * 取一行文本开头的空格。
     *
     * @param line 一行文本
     * @return 开头连续空格，可能为空串
     */
    private static String leadingSpaces(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return line.substring(0, index);
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
