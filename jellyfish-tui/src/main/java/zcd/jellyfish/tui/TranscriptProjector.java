package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.ControlChars;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.LineWrapper;
import zcd.jellyfish.tui.text.MarkdownRenderer;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
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
 *     助手正文（markdown：标题 / 列表 / 引用 / 代码块 / 行内样式）
 *       ⎿ 工具轨迹（暗色）
 *       │   工具执行中的实时输出（暗色，只留末尾若干行，工具一返回就被正式结果取代）
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

    /** 助手正文缩进的列宽：markdown 渲染要按「屏幕列数 − 缩进」算可用宽度。 */
    private static final int BODY_INDENT_WIDTH = DisplayWidth.of(BODY_INDENT);

    /** 工具轨迹前缀。 */
    static final String TRACE_PREFIX = "      \u23bf ";

    /**
     * 运行中工具输出的前缀。
     * <p>
     * 与 {@link #TRACE_PREFIX} 等宽但换一个字符：输出行是工具轨迹的延续，用竖线表明「都在同一个工具里」，
     * 而不是又开了一个工具。
     */
    static final String TOOL_OUTPUT_PREFIX = "      \u2502 ";

    /** 失败标记：与工具名同行，一眼能看出这条轨迹没跑成。 */
    static final String WARNING_MARK = "\u26a0";

    /**
     * 运行中工具目标的显示列上限。
     * <p>
     * <b>为什么按列不按码点</b>：全角字符每字占 2 列，用码点数当上限会让一条标签在中文参数下
     * 折成好几行。这是「一眼扫过的信号」，不是看全文的地方。
     * <p>
     * 它只是上限；实际预算还要减去前缀与工具名占用的列（见 {@link #targetSuffix}），
     * 否则一条 120 列的标签照样会在 80 列终端上折行。
     */
    private static final int MAX_TARGET_COLUMNS = 120;

    /** 运行中目标与工具名之间的分隔符，与结果轨迹行的摘要分隔符保持一致。 */
    private static final String TARGET_SEPARATOR = " \u00b7 ";

    /** 思考过程前缀。 */
    static final String THINKING_PREFIX = "      \u273b ";

    /** 展开态思考过程的开关提示；折叠态才带，展开后重复提示只是噪音。 */
    private static final String THINKING_EXPAND_HINT = "，Ctrl+T 展开";

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

    /** 首页垂直居中时至少留出的顶部空行数（见 {@link #centerVertically}）。 */
    private static final int TOP_BLANK_ROWS = 1;

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
     * <p>
     * <b>整块内容按视口高度垂直居中</b>：首页的内容天然不足一屏（字标只有几行），
     * 全部顶在上边框会留下一大片只能算「空」的空白。留白必须在这里补而不是在
     * {@link HomeSplash} 里补——提示也要参与排版，只按字标居中会在有提示时整体偏上。
     *
     * @param notices      外壳提示列表（按插入顺序，时间戳非递减），可为 {@code null}（当作空）
     * @param width        可用列数，小于 1 时按 1 处理
     * @param viewportRows 消息区可用行数，小于 1 时按 0 处理
     * @return 视觉行列表，保证非 {@code null}
     */
    public static List<VisualLine> home(List<ShellNotice> notices, int width, int viewportRows) {
        List<VisualLine> content = new ArrayList<VisualLine>();
        content.addAll(HomeSplash.lines(width));
        if (notices != null) {
            for (ShellNotice notice : notices) {
                content.addAll(notice(notice, width));
            }
        }
        return centerVertically(content, viewportRows);
    }

    /**
     * 在内容上方补空行，使整块在视口内垂直居中。
     * <p>
     * <b>至少留一行</b>：字标贴着消息区上边框显得局促，这一行也是原来「首个为空行」的观感。
     * 因此可用空间只剩奇数行时，多出来的那一行算在下边，整块略偏上。
     * <p>
     * <b>内容比视口高时只补这一行</b>：此时补了也看不见（窗口跟着末尾走），
     * 而滚动位置与总行数的算法不必为首页开特例。
     *
     * @param content      内容行
     * @param viewportRows 消息区可用行数
     * @return 补齐留白后的行列表；内容本就够高时原样返回
     */
    private static List<VisualLine> centerVertically(List<VisualLine> content, int viewportRows) {
        int padding = Math.max(TOP_BLANK_ROWS, (Math.max(0, viewportRows) - content.size()) / 2);
        if (padding <= 0) {
            return content;
        }
        List<VisualLine> out = new ArrayList<VisualLine>(content.size() + padding);
        for (int i = 0; i < padding; i++) {
            out.add(VisualLine.EMPTY);
        }
        out.addAll(content);
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
     * @param thinkingExpanded 是否展开思考过程：{@code false} 时每个思考块压成一行
     * @return 视觉行列表，保证非 {@code null}
     */
    public static List<VisualLine> project(List<SessionMessage> messages, List<ShellNotice> notices,
                                           InflightTurn.Snapshot inflight, int width, int maxMessages,
                                           boolean thinkingExpanded) {
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
                insideAssistantBlock = appendAssistant(out, insideAssistantBlock, content,
                        message.getThinking(), thinkingExpanded, width);
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
        appendInflight(out, inflight, thinkingExpanded, insideAssistantBlock, width);
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
     * @param thinking     思考过程，可为 {@code null}
     * @param expanded     是否展开思考过程
     * @param width        可用列数
     * @return 投影后是否处于助手块内
     */
    private static boolean appendAssistant(List<VisualLine> out, boolean inBlock, String content,
                                           String thinking, boolean expanded, int width) {
        boolean hasBody = !isBlank(content);
        boolean hasThinking = !isBlank(thinking);
        if (!hasBody && !hasThinking) {
            // 只带工具调用的轮次没有正文，此处不落任何行；表头由随后的 tool 轨迹补上
            return inBlock;
        }
        if (!inBlock) {
            out.add(VisualLine.EMPTY);
            out.add(VisualLine.of(new StyledSegment(ASSISTANT_HEADER, ASSISTANT_HEADER_STYLE)));
        }
        if (hasThinking) {
            appendThinking(out, thinking, expanded, false, width);
        }
        if (hasBody) {
            appendMarkdown(out, content, width);
        }
        return true;
    }

    /**
     * 投影一个思考块：展开态铺全部内容，折叠态压成一行并报字数。
     * <p>
     * <b>为什么折叠态也要报字数</b>：只给一个「思考过程」标签的话，用户无法判断这块值不值得展开；
     * 字数是一个不用展开就能得到的「性价比」信号。字数用 {@code codePointCount} 数，
     * 否则一个 emoji（代理对）会被算成两个字。
     * <p>
     * <b>为什么折叠提示带 {@code Ctrl+T} 而流式提示不带</b>：历史块是「已封存的思考」，
     * 用户看到它时的疑问是「怎么看全文」；流式块还在增长，此刻想说的是「模型正在想」，
     * 而且这时按 {@code Ctrl+T} 也会立刻生效，提示反而抢走注意力。
     *
     * @param out       输出列表
     * @param thinking  思考过程正文，保证非空白
     * @param expanded  是否展开
     * @param streaming 是否来自进行中回合（只影响折叠态文案）
     * @param width     可用列数
     */
    private static void appendThinking(List<VisualLine> out, String thinking, boolean expanded,
                                       boolean streaming, int width) {
        if (expanded) {
            out.addAll(LineWrapper.wrap(new StyledSegment(THINKING_PREFIX, THINKING_STYLE),
                    wrapBody(thinking, THINKING_STYLE), width));
            return;
        }
        String label = streaming
                ? "思考中\u2026（" + charCount(thinking) + " 字）"
                : "思考过程（" + charCount(thinking) + " 字" + THINKING_EXPAND_HINT + "）";
        out.addAll(LineWrapper.wrap(new StyledSegment(THINKING_PREFIX, FOLDED_STYLE),
                wrapBody(label, FOLDED_STYLE), width));
    }

    /**
     * 数一段文本的码点数。
     *
     * @param text 文本，保证非 {@code null}
     * @return 码点数
     */
    private static int charCount(String text) {
        return text.codePointCount(0, text.length());
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
        List<StyledSegment> body = new ArrayList<StyledSegment>();
        body.addAll(wrapBody(label, TRACE_STYLE));
        // 摘要用与工具名相同的样式：它是「刚才那一行到底是什么事」的说明，不是一条警示。
        // 放在失败后缀之前，于是「哪个工具 · 它在干什么 · 成没成」从左到右顺着读下来。
        // 两段都可能来自不可信输入（插件 / 工具自定的 terminal 值），因此过一道控制字符过滤
        body.addAll(wrapBody(ControlChars.strip(summarySuffix(message)), TRACE_STYLE));
        // 错误用红色后缀而不是把整行变红：工具名与结论要能一起读，整行染色会让
        // 「哪个工具失败了」这条信息淹没在颜色里。判据来自元数据字段，不去解析首行文案
        body.addAll(wrapBody(ControlChars.strip(failureSuffix(message)), ERROR_STYLE));
        out.addAll(LineWrapper.wrap(new StyledSegment(TRACE_PREFIX, TRACE_STYLE), body, width));
        return true;
    }

    /**
     * 取工具轨迹的单行摘要后缀。
     * <p>
     * <b>为什么读元数据而不是读结果正文的首行</b>：首行那句「子代理 scout · 3 轮」是给<b>模型</b>
     * 读的措辞，展示若依赖它，改一个句子标记就会消失。元数据里的摘要键是同一件事的另一个版本，
     * 它由工具自己声明「这是给人看的」，因此措辞怎么改都不会影响渲染。
     * <p>
     * <b>为什么外壳不自己去拼这句话</b>：外壳认识的是「有没有摘要」，而不是具体是哪个工具、
     * 该带哪几个数字——一旦外壳开始按工具名分支，每多一个工具就多一处特例。
     * <p>
     * <b>为什么结果正文不显示而摘要显示</b>：正文往往是一整篇报告，塞进消息区会把对话刷爆
     * （它本来就是回灌给模型的长文本），而一行摘要回答的正好是「屏幕上少了正文之后，
     * 我刚才能看到的东西还在不在」。
     *
     * @param message 工具消息，不可为 {@code null}
     * @return 后缀文本；没有摘要时返回空串
     */
    private static String summarySuffix(SessionMessage message) {
        String summary = ToolMetadata.summaryOf(message.getMetadata());
        return summary.isEmpty() ? "" : " \u00b7 " + summary;
    }

    /**
     * 取工具轨迹的失败后缀。
     * <p>
     * <b>为什么读元数据而不是读首行文案</b>：首行那句「exit: 1」是给模型看的措辞，
     * 展示若依赖它，改一个措辞标记就会消失。元数据是同一份事实的结构化版本，
     * 判据（退出码非零或非正常终止）由 {@code ToolMetadata#failed} 统一给出。
     * <p>
     * <b>为什么只标失败</b>：成功是常态，逐条标「exit: 0」只会把真正需要一眼看见的那几条埋掉。
     *
     * @param message 工具消息，不可为 {@code null}
     * @return 后缀文本；成功或没有元数据时返回空串
     */
    private static String failureSuffix(SessionMessage message) {
        Map<String, Object> metadata = message.getMetadata();
        if (!ToolMetadata.failed(metadata)) {
            return "";
        }
        Object exitCode = metadata.get(ToolMetadata.KEY_EXIT_CODE);
        if (exitCode instanceof Number) {
            return " " + WARNING_MARK + " 退出码 " + exitCode;
        }
        Object terminal = metadata.get(ToolMetadata.KEY_TERMINAL);
        return " " + WARNING_MARK + " " + terminal;
    }

    /**
     * 投影进行中回合。
     *
     * @param out      输出列表
     * @param inflight 暂存区快照
     * @param thinkingExpanded 是否展开思考过程
     * @param width    可用列数
     * @param insideAssistantBlock 投影到这里时是否已在助手块内（决定要不要补表头）
     */
    private static void appendInflight(List<VisualLine> out, InflightTurn.Snapshot inflight,
                                       boolean thinkingExpanded, boolean insideAssistantBlock, int width) {
        String thinking = inflight.getThinking();
        String text = inflight.getText();
        InflightTurn.Outcome outcome = inflight.getOutcome();

        if (outcome == InflightTurn.Outcome.RUNNING) {
            boolean hasBody = !isBlank(text) || !isBlank(thinking);
            if (!hasBody) {
                // 工具在跑：显示它的名字与实时输出末尾若干行。
                // 这个分支必须排在「处理中…」之前——命令行可能跑几分钟，在那几分钟里
                // 「处理中…」传达的信息量是零，而一条卡死的命令与一条在跑的看起来完全一样
                if (appendRunningTool(out, inflight, insideAssistantBlock, width)) {
                    return;
                }
                // 没有可显示增量时给一个「还在干活」的信号，否则屏幕看起来像卡死了
                out.add(VisualLine.of(new StyledSegment(NOTICE_PREFIX + "处理中\u2026", PENDING_STYLE)));
                return;
            }
            out.add(VisualLine.EMPTY);
            out.add(VisualLine.of(new StyledSegment(ASSISTANT_HEADER, ASSISTANT_HEADER_STYLE)));
            if (!isBlank(thinking)) {
                appendThinking(out, thinking, thinkingExpanded, true, width);
            }
            if (!isBlank(text)) {
                appendMarkdown(out, text, width);
            }
            if (inflight.isTextTruncated()) {
                out.add(VisualLine.of(new StyledSegment(NOTICE_PREFIX + "内容过长，仅显示末尾", FOLDED_STYLE)));
            }
            return;
        }
        appendOutcomeNotice(out, inflight, width);
    }

    /**
     * 投影运行中的工具轨迹（工具名 + 实时输出的末尾若干行）。
     * <p>
     * <b>为什么必须有这一块</b>：命令行的输出是在工具<b>返回之前</b>产生的，而这段时间里会话里
     * 还没有 tool 消息。没有它，屏幕上只剩「处理中…」——用户无法区分「还在跑」与「卡住了」。
     * <p>
     * <b>它为什么不用标「已截断」</b>：行数与行内字符数都由 {@link InflightTurn} 截过，
     * 但工具一返回，这里就会被会话投影出的正式轨迹与结果取代。在这里标「已截断」
     * 会被读成「工具结果被截断了」，而真正会截断结果的是内核的截断中间件，它有自己的一套标识。
     * <p>
     * <b>表头为什么不无条件补</b>：行到这里时通常已经在助手块内（上一轮 assistant 消息刚落库），
     * 再打一个表头会让屏幕上出现两个连续的表头。沿用 {@code appendToolTrace} 的同一判断。
     *
     * @param out      输出列表
     * @param inflight 暂存区快照
     * @param insideAssistantBlock 是否已在助手块内
     * @param width    可用列数
     * @return 是否产出了内容
     */
    private static boolean appendRunningTool(List<VisualLine> out, InflightTurn.Snapshot inflight,
                                             boolean insideAssistantBlock, int width) {
        String toolName = inflight.getRunningToolName();
        List<String> lines = inflight.getToolOutputLines();
        if (toolName == null && lines.isEmpty()) {
            return false;
        }
        out.add(VisualLine.EMPTY);
        if (!insideAssistantBlock) {
            out.add(VisualLine.of(new StyledSegment(ASSISTANT_HEADER, ASSISTANT_HEADER_STYLE)));
        }
        String label = toolName == null || toolName.isEmpty() ? "工具" : toolName;
        out.addAll(LineWrapper.wrap(new StyledSegment(TRACE_PREFIX, TRACE_STYLE),
                wrapBody(label + targetSuffix(inflight, label, width), TRACE_STYLE), width));
        for (String line : lines) {
            // 控制字符必须在显示边界上滤掉：命令输出里的一个 ESC 序列能改写屏幕。
            // 与 MarkdownRenderer / ApprovalPrompt 同一处理位置
            String filtered = ControlChars.strip(line);
            if (filtered == null || filtered.isEmpty()) {
                out.add(VisualLine.EMPTY);
                continue;
            }
            out.addAll(LineWrapper.wrap(new StyledSegment(TOOL_OUTPUT_PREFIX, TRACE_STYLE),
                    wrapBody(filtered, TRACE_STYLE), width));
        }
        return true;
    }

    /**
     * 取运行中工具的目标后缀：把工具参数渲染成一行「它在动什么」。
     * <p>
     * <b>为什么显示参数而不是靠工具自报</b>：工具此刻还没返回，没有 metadata 可用；
     * 参数是这一时刻唯一能说明目标的输入。规则本身与工具名无关（有参数就显示），
     * 因此不破坏「外壳对工具一无所知」的前提。
     * <p>
     * <b>为什么按显示列硬截断而不是折行</b>：这是「一眼扫过的信号」，不是看全文的地方——
     * 要看全文有审批浮层。折行会把一条轨迹撑成好几行，把后面的轨迹挤下去。
     *
     * @param inflight 暂存区快照
     * @param label    工具名（已处理空值）
     * @param width    可用列数
     * @return 后缀文本；没有参数或放不下时返回空串
     */
    private static String targetSuffix(InflightTurn.Snapshot inflight, String label, int width) {
        Map<String, Object> arguments = inflight.getRunningToolArguments();
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        // argumentsOf 已经过滤控制字符并对敏感键脱敏；这里再压掉它刻意保留的换行
        String rendered = collapseToSingleLine(ApprovalPrompt.argumentsOf(arguments));
        // 预算要减去前缀与工具名，否则 120 列的上限在窄终端上依旧会折行
        int used = DisplayWidth.of(TRACE_PREFIX) + DisplayWidth.of(label) + DisplayWidth.of(TARGET_SEPARATOR);
        int budget = Math.min(MAX_TARGET_COLUMNS, width - used);
        if (budget < 1) {
            return "";
        }
        return TARGET_SEPARATOR + truncateToColumns(rendered, budget);
    }

    /**
     * 把文本压成单行：连续的空白（含换行）折成一个空格。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 单行文本，保证非 {@code null}
     */
    private static String collapseToSingleLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && sb.length() > 0) {
                sb.append(' ');
            }
            pendingSpace = false;
            sb.appendCodePoint(codePoint);
        }
        return sb.toString();
    }

    /**
     * 按显示列数截断文本，超出部分用省略号代替。
     *
     * @param text       文本，保证非 {@code null}
     * @param maxColumns 显示列上限
     * @return 截断后的文本
     */
    private static String truncateToColumns(String text, int maxColumns) {
        if (DisplayWidth.of(text) <= maxColumns) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int width = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            int next = DisplayWidth.ofCodePoint(codePoint);
            // 留一列给省略号，保证结果整体不超过 maxColumns
            if (width + next > maxColumns - 1) {
                break;
            }
            sb.appendCodePoint(codePoint);
            width += next;
        }
        return sb.append('\u2026').toString();
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
     * 把一段 markdown 正文铺成视觉行，并补上消息缩进。
     * <p>
     * <b>渲染与缩进为什么要分成两步</b>：{@link MarkdownRenderer} 不认识消息缩进，
     * 它只按「可用列数」排版（列表标记、引用竖线都在这个列数之内）；
     * 本方法负责把那个可用列数先减掉缩进，再给每条结果行补上缩进。
     * 两者相减就是屏幕列数，因此交付给渲染引擎的宽度恒不超过屏幕宽度——
     * 越界会让终端自行折行，而那种折行不参与视觉行计数，滚动位置立刻错位。
     *
     * @param out     输出列表
     * @param content markdown 正文
     * @param width   可用总列数
     */
    private static void appendMarkdown(List<VisualLine> out, String content, int width) {
        int bodyWidth = Math.max(1, width - BODY_INDENT_WIDTH);
        for (VisualLine line : MarkdownRenderer.render(content, bodyWidth, BODY_STYLE)) {
            out.add(indent(line));
        }
    }

    /**
     * 给一条正文视觉行补上消息缩进。
     *
     * @param line 视觉行，不可为 {@code null}
     * @return 补好缩进的视觉行
     */
    private static VisualLine indent(VisualLine line) {
        if (line.isEmpty()) {
            // 空行不补缩进：补了就是行尾空白，不但白占字节，滚动时看着也像脏东西
            return VisualLine.EMPTY;
        }
        List<StyledSegment> segments = new ArrayList<StyledSegment>(line.getSegments().size() + 1);
        segments.add(new StyledSegment(BODY_INDENT, Style.EMPTY));
        segments.addAll(line.getSegments());
        return new VisualLine(segments);
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
