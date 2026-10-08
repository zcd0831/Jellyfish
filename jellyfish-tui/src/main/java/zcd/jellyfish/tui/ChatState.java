package zcd.jellyfish.tui;

import zcd.jellyfish.api.extension.ToolRenderHint;
import zcd.jellyfish.core.input.InputDirectiveRun;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 视图状态：唯一持有「用户正在看哪一段」的地方。
 * <p>
 * <b>职责边界</b>：本类只决定「看投影结果的哪一段」，不决定「投影结果里有什么」——
 * 后者完全属于 {@link TranscriptProjector}。把两者分开，是为了让「会话切了 / 插件写了历史」
 * 这类外部变化自动反映到屏幕：内容由会话投影得出，本类不必知道发生过什么。
 * <p>
 * <b>为什么滚动偏移是自己的字段而不是框架的</b>：冒烟实测 TamboUI 的 {@code ScrollableElement}
 * 把 {@code ScrollbarState} 作为实例字段，每帧重建元素就会让滚动位置归零；且它的
 * {@code ContainerElement.add()} 只是 {@code List#add}，复用实例逐帧追加会让子元素无限增长，
 * 而子元素数过百就会撞上布局断崖。因此在「每帧内容都变 + 滚动位置要保留」这个组合下，
 * 滚动必须自己管。附带好处是跟随底部的判据从「框架内部状态」变成了一个我们自己的整数比较。
 * <p>
 * <b>线程契约</b>：本类<b>只由渲染线程读写</b>。{@link InflightTurn} 由 {@code react} 线程写、
 * 渲染线程读，两者通过 {@link InflightTurn#snapshot()} 交接，本类不直接碰它的内部状态。
 *
 * @author zcd
 */
public final class ChatState {

    /** 暂存区：进行中回合的流式增量。 */
    private final InflightTurn inflight = new InflightTurn();

    /** 是否跟随内容末尾：为真时每帧把窗口对齐到最后一行。 */
    private boolean followTail = true;

    /** 上一次渲染得到的窗口偏移（视觉行）。不跟随时，用户位置由它固定住。 */
    private int offset;

    /** 上一次渲染时的总行数。 */
    private int totalRows;

    /** 上一次渲染时的窗口行数。 */
    private int viewportRows;

    /**
     * 当前进行中的输入指令句柄，无指令时为 {@code null}。
     * <p>
     * <b>为什么与回合共用暂存区</b>：{@code !} 这类指令同样会产出实时输出，复用 {@link InflightTurn}
     * 才能共用 {@link TranscriptProjector} 的同一套渲染，不必为它开第二条投影路径。
     * <p>
     * <b>为什么只有指令句柄、没有回合句柄</b>：在途回合与取消入口已收归内核的 {@code TurnRegistry}
     * （{@code ConversationService.submit} 先占位、终态自动归还），本类再存一份就成了第二个真源。
     */
    private InputDirectiveRun currentDirective;

    /** 最近一次会话标识，用于识别会话切换。 */
    private String lastSessionId;

    /** 最近一次会话消息条数。 */
    private int lastMessageCount = -1;

    /** 最近一条消息的标识，用于识别「条数没变但内容变了」的替换场景。 */
    private String lastMessageId;

    /** 最近一次投影的列数，-1 表示尚未投影过。 */
    private int lastWidth = -1;

    /**
     * 最近一次投影时的视口行数，-1 表示尚未投影过。
     * <p>
     * <b>它也是投影的输入</b>：首页内容要按视口高度垂直居中（{@link TranscriptProjector#home}），
     * 因此终端高度变化后必须重算——只比列数的话，纵向拉伸终端会留下按旧高度算出来的留白。
     */
    private int lastViewportRows = -1;

    /** 最近一次投影使用的消息上限。 */
    private int lastMaxMessages = -1;

    /** 最近一次投影时的思考展开状态。 */
    private boolean lastThinkingExpanded;

    /** 最近一次投影时的工具参数展开状态。 */
    private boolean lastToolArgumentsExpanded;

    /** 上次投影用的工具行渲染提示表，按实例比对（见 {@code refreshProjection}）。 */
    private Map<String, ToolRenderHint> lastToolHints = Collections.emptyMap();

    /** 最近一次投影时的提示版本号。 */
    private int lastNoticeVersion = -1;

    /** 最近一次投影的结果，作为「什么都没变」时的复用对象。 */
    private List<VisualLine> projected = Collections.emptyList();

    /**
     * 外壳提示缓冲（插件通知与状态反馈），参与投影时按时间戳与会话消息归并。
     * <p>
     * <b>命令结果不在这里</b>：它走 {@code ShellOutput}（命令输出面板）。两者分开是因为生命周期不同——
     * 面板只留最近一次、可关可滚；而这里是「按时间发生的事」，要按时间戳插进消息流。
     */
    private final List<ShellNotice> notices = new ArrayList<ShellNotice>();

    /**
     * 思考过程是否展开。
     * <p>
     * <b>为什么是全局开关而不是逐块展开</b>：屏幕上没有「选中某条消息」这种交互模型，
     * 逐块展开就必须引入选择态、焦点管理以及「滚动位置该锚在哪里」这套账；
     * 而用户真正想要的是「一堆思考要么都看、要么都不看」。
     * <p>
     * 默认折叠：思考过程通常比正文长好几倍，默认展开会把正文推到屏幕外。
     */
    private boolean thinkingExpanded;

    /**
     * 工具调用参数是否展开。
     * <p>
     * 与 {@link #thinkingExpanded} 同一套取舍：全局开关而不是逐条展开。参数默认只折行到
     * {@code TranscriptProjector#MAX_ARGUMENT_ROWS} 行为止，展开后放宽到
     * {@code MAX_ARGUMENT_ROWS_EXPANDED} 行——展开是「把超大参数铺开」，不是「无界地铺」。
     */
    private boolean toolArgumentsExpanded;

    /** 提示缓冲版本号，参与投影缓存判据。 */
    private int noticeVersion;

    /** 提示条数上限：它是附属于界面的反馈，不应无界增长。 */
    static final int MAX_NOTICES = 50;

    /**
     * 同一来源的插件通知显示上限。
     * <p>
     * <b>为什么要有这一档而不是只看全局上限</b>：全局上限会让一个刷屏的插件把别的插件的通知
     * 一起挤掉。按来源封顶才是「一个插件不该占满屏幕」这句话的实际含义。
     * <p>
     * 淘汰的是<b>该来源最早的那一条</b>：插件通知多是进度 / 状态类，留着旧的不如留新的。
     */
    static final int MAX_NOTICES_PER_PLUGIN = 3;

    /**
     * 追加一条带原文回显的外壳提示。
     * <p>
     * 只由渲染线程调用。提示不进会话（见 {@link ShellNotice}），但带自己的时间戳参与投影：
     * 它因此出现在实际发生的时刻上，而不是永远贴在屏幕底部。
     * <p>
     * <b>命令结果不走这里</b>：它进 {@code ShellOutput}（命令输出面板）。这里剩下的是状态反馈——
     * 「回合进行中」这类「对刚发生的事的说明」。
     *
     * @param command 触发本提示的原文，可为 {@code null} 或空白（不回显）
     * @param text    提示文本，{@code null} 或空白忽略
     * @param kind    提示语义，不可为 {@code null}
     */
    public void appendNotice(String command, String text, ShellNotice.Kind kind) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (notices.size() >= MAX_NOTICES) {
            notices.remove(0);
        }
        notices.add(new ShellNotice(System.currentTimeMillis(), command, text, kind));
        noticeVersion++;
    }

    /**
     * 追加一条无命令可回显的外壳提示（状态反馈）。
     *
     * @param text 提示文本，{@code null} 或空白忽略
     * @param kind 提示语义，不可为 {@code null}
     */
    public void appendNotice(String text, ShellNotice.Kind kind) {
        appendNotice(null, text, kind);
    }

    /**
     * 追加一条插件来源的外壳提示。
     * <p>
     * 与状态反馈走同一个缓冲区（因此同样按时间戳参与投影），区别只在两条纪律：
     * <ul>
     *     <li><b>按来源封顶</b>：同一 owner 已显示满 {@link #MAX_NOTICES_PER_PLUGIN} 条时，
     *     先把它最早的那一条挤掉——否则一个插件就能把屏幕刷满；</li>
     *     <li><b>文本已经过滤过控制字符</b>：那是渲染面（{@code TuiApp}）的责任，这里不再改文本。
     *     过滤必须发生在写入之前——{@code TranscriptProjector} 对提示块不做过滤（只对工具输出做）。</li>
     * </ul>
     *
     * @param owner 来源 owner（插件标识或 {@code 插件标识::子标识}），不可为空白
     * @param text  提示文本，{@code null} 或空白忽略
     * @param kind  提示语义，不可为 {@code null}
     */
    public void appendPluginNotice(String owner, String text, ShellNotice.Kind kind) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        evictOldestPluginNotice(owner);
        if (notices.size() >= MAX_NOTICES) {
            notices.remove(0);
        }
        notices.add(ShellNotice.plugin(System.currentTimeMillis(), owner, text, kind));
        noticeVersion++;
    }

    /**
     * 把某个来源超额的插件通知挤掉。
     *
     * @param owner 来源 owner
     */
    private void evictOldestPluginNotice(String owner) {
        int count = 0;
        int oldest = -1;
        for (int i = 0; i < notices.size(); i++) {
            if (!owner.equals(notices.get(i).getOwner())) {
                continue;
            }
            if (oldest < 0) {
                oldest = i;
            }
            count++;
        }
        if (count >= MAX_NOTICES_PER_PLUGIN && oldest >= 0) {
            notices.remove(oldest);
        }
    }

    /**
     * 切换思考过程展开状态。
     *
     * @return 切换后的状态（{@code true} 为已展开）
     */
    public boolean toggleThinking() {
        thinkingExpanded = !thinkingExpanded;
        return thinkingExpanded;
    }

    /**
     * 切换工具调用参数展开状态。
     *
     * @return 切换后的状态（{@code true} 为已展开）
     */
    public boolean toggleToolArguments() {
        toolArgumentsExpanded = !toolArgumentsExpanded;
        return toolArgumentsExpanded;
    }

    /**
     * 判断工具调用参数是否展开。
     *
     * @return 展开返回 {@code true}
     */
    public boolean isToolArgumentsExpanded() {
        return toolArgumentsExpanded;
    }

    /**
     * 清空外壳提示缓冲。
     */
    public void clearNotices() {
        if (notices.isEmpty()) {
            return;
        }
        notices.clear();
        noticeVersion++;
    }

    /**
     * 获取暂存区，供 {@link TuiTurnListener} 写入。
     *
     * @return 暂存区，保证非 {@code null}
     */
    public InflightTurn getInflight() {
        return inflight;
    }

    /**
     * 开始一件会产出实时输出的工作（回合或输入指令）：重置暂存区并清除指令句柄。
     * <p>
     * <b>为什么它是一个独立入口</b>：分流的顺序现在归内核（{@code ConversationService}），
     * 外壳在提交之前还不知道会落进回合还是指令，但两者都要求在<b>提交之前</b>重置暂存区——
     * 晚一步重置就会把执行线程已经写出的第一段实时输出抹掉。因此先用本方法重置，
     * 拿到结果后指令路径再用 {@link #bindDirective} 绑定句柄；
     * 落进非回合路时由外壳静默收回（同一渲染帧内完成，用户看不到中间态）。
     * <p>
     * 暂存区的重置必须发生在这里（而不是在某个回调里）：回调开始时模型可能还没吐出任何字符，
     * 那时已经是 {@code RUNNING}，但上一回合的终局若不先清掉，投影器会把它当成已结束回合。
     */
    public void beginWork() {
        inflight.begin();
        this.currentDirective = null;
    }

    /**
     * 绑定当前进行中的输入指令，供轮询收尾与 {@code Esc} 取消。
     *
     * @param run 指令句柄，可为 {@code null}（表示清空）
     */
    public void bindDirective(InputDirectiveRun run) {
        this.currentDirective = run;
    }

    /**
     * 取当前进行中的输入指令。
     *
     * @return 指令句柄；无进行中指令时为 {@code null}
     */
    public InputDirectiveRun getDirective() {
        return currentDirective;
    }

    /**
     * 清除当前指令句柄（执行已结束）。
     */
    public void clearDirective() {
        this.currentDirective = null;
    }

    /**
     * 判断当前是否有进行中的工作（回合或输入指令）。
     * <p>
     * 判据是暂存区状态而不是内核的回合表：它回答的是「界面此刻该不该显示进行中、该不该拒绝新输入」，
     * 而指令不在内核的回合闸门里，只在本类里有状态。
     *
     * @return 有进行中工作返回 {@code true}
     */
    public boolean isTurnRunning() {
        return inflight.isRunning();
    }

    /**
     * 中断当前进行中的输入指令。
     * <p>
     * <b>本类只负责指令</b>：回合的取消由调用方走内核的 {@code TurnRegistry.cancel(sessionId)}
     * ——回合句柄不在本类里（见 {@link #currentDirective} 的注释）。
     * <p>
     * 中断必须由渲染线程主动调用（而不是等执行线程投递消息），否则用户按下 {@code Esc}
     * 之后界面上不会有任何立刻可见的反应——那与「卡死」无法区分。句柄为空或已结束时什么都不做，
     * 因此重复按 {@code Esc} 是安全的。
     */
    public void cancelDirective() {
        InputDirectiveRun run = currentDirective;
        if (run != null) {
            run.cancel();
        }
    }

    /**
     * 计算本帧要显示的窗口。
     * <p>
     * 每帧都会重新投影（成本已实测：3000 行约 1.96ms，远低于 38ms 的帧预算），
     * 但在「会话未变、暂存区未变、尺寸未变」时直接复用上一次的结果，
     * 因为渲染引擎在无输入时也会以约 26fps 持续调用本方法。
     *
     * @param sessionId   当前会话标识，可为 {@code null}（表示首页，无当前会话）
     * @param messages    当前会话消息列表，可为 {@code null}
     * @param width       消息区可用列数
     * @param viewportRows 消息区可用行数
     * @param maxMessages 投影的消息条数上限
     * @param toolHints   工具行渲染提示（按工具名），不可为 {@code null}
     * @return 本帧窗口，保证非 {@code null}
     */
    public View view(String sessionId, List<SessionMessage> messages, int width, int viewportRows,
                     int maxMessages, Map<String, ToolRenderHint> toolHints) {
        refreshProjection(sessionId, messages, width, viewportRows, maxMessages, toolHints);
        int rows = Math.max(1, viewportRows);
        int maxOffset = Math.max(0, totalRows - rows);
        this.viewportRows = rows;

        if (followTail) {
            offset = maxOffset;
        } else {
            offset = Math.max(0, Math.min(offset, maxOffset));
            // 用户翻到了最底部就恢复跟随：把「是否跟随」锚在可观测的位置上，
            // 而不是去推断「这次滚动是用户操作还是程序追加」——后者在立即模式下不可靠
            if (offset >= maxOffset) {
                followTail = true;
            }
        }

        int from = Math.min(offset, totalRows);
        int to = Math.min(totalRows, from + rows);
        List<VisualLine> window = projected.subList(from, to);
        int hiddenBelow = totalRows - to;
        return new View(window, hiddenBelow, offset > 0, followTail, inflight.isRunning());
    }

    /**
     * 向上滚动若干行，并停止跟随底部。
     *
     * @param rows 行数，小于 1 时忽略
     */
    public void scrollUp(int rows) {
        if (rows < 1) {
            return;
        }
        followTail = false;
        offset = Math.max(0, offset - rows);
    }

    /**
     * 向下滚动若干行。
     *
     * @param rows 行数，小于 1 时忽略
     */
    public void scrollDown(int rows) {
        if (rows < 1) {
            return;
        }
        int maxOffset = Math.max(0, totalRows - Math.max(1, viewportRows));
        offset = Math.min(maxOffset, offset + rows);
        if (offset >= maxOffset) {
            followTail = true;
        }
    }

    /**
     * 向上翻一页。
     */
    public void pageUp() {
        scrollUp(Math.max(1, viewportRows - 1));
    }

    /**
     * 向下翻一页。
     */
    public void pageDown() {
        scrollDown(Math.max(1, viewportRows - 1));
    }

    /**
     * 跳到底部并恢复跟随。
     */
    public void toBottom() {
        followTail = true;
    }

    /**
     * 判断当前是否跟随内容末尾。
     *
     * @return 跟随返回 {@code true}
     */
    public boolean isFollowingTail() {
        return followTail;
    }

    /**
     * 取当前窗口偏移（视觉行）。
     *
     * @return 偏移
     */
    public int getOffset() {
        return offset;
    }

    /**
     * 取最近一次投影的总行数。
     *
     * @return 总行数
     */
    public int getTotalRows() {
        return totalRows;
    }

    /**
     * 按需重算投影结果。
     *
     * @param sessionId    会话标识
     * @param messages     消息列表
     * @param width        可用列数
     * @param viewportRows 消息区可用行数
     * @param maxMessages  消息条数上限
     */
    private void refreshProjection(String sessionId, List<SessionMessage> messages, int width,
                                   int viewportRows, int maxMessages,
                                   Map<String, ToolRenderHint> toolHints) {
        List<SessionMessage> source = messages == null ? Collections.<SessionMessage>emptyList() : messages;
        String lastId = source.isEmpty() ? null : source.get(source.size() - 1).getMessageId();
        boolean unchanged = lastWidth == width
                && lastViewportRows == viewportRows
                && lastMaxMessages == maxMessages
                && lastThinkingExpanded == thinkingExpanded
                && lastToolArgumentsExpanded == toolArgumentsExpanded
                // 按<b>实例</b>比对：提示表由 UiCache 在失效时才重建，同一个实例就代表内容没变。
                // 逐项比对一张可能上百项的表，只为了得出同一结论，没有意义
                && lastToolHints == toolHints
                && lastMessageCount == source.size()
                && lastNoticeVersion == noticeVersion
                && Objects.equals(lastMessageId, lastId)
                && Objects.equals(lastSessionId, sessionId)
                && !inflight.isDirty();
        if (unchanged) {
            return;
        }
        // 先清脏标记再取快照：若 append 追加发生在两者之间，它会把 dirty 重新置为 true，
        // 下一帧会再刷一次。反过来（先快照后清）会把「最后一个 token」的变更吃掉，
        // 而流式结束时通常不再有下一次追加，屏幕上会永久少了最后一截。
        inflight.clearDirty();
        InflightTurn.Snapshot snapshot = inflight.snapshot();
        if (sessionId == null) {
            // 无当前会话 = 首页：投影字标与外壳提示（见 TranscriptProjector.home）
            projected = TranscriptProjector.home(notices, width, viewportRows);
        } else {
            projected = TranscriptProjector.project(source, notices, snapshot, width, maxMessages,
                    thinkingExpanded, toolArgumentsExpanded, toolHints);
        }
        lastWidth = width;
        lastViewportRows = viewportRows;
        lastMaxMessages = maxMessages;
        lastThinkingExpanded = thinkingExpanded;
        lastToolArgumentsExpanded = toolArgumentsExpanded;
        lastToolHints = toolHints;
        lastMessageCount = source.size();
        lastMessageId = lastId;
        lastSessionId = sessionId;
        lastNoticeVersion = noticeVersion;
        totalRows = projected.size();
    }

    /**
     * 一帧要显示的消息区内容。
     */
    public static final class View {

        /** 本帧可见的视觉行。 */
        private final List<VisualLine> lines;

        /** 窗口下方还有多少行没显示。 */
        private final int hiddenBelowRows;

        /** 窗口上方是否还有内容。 */
        private final boolean hiddenAbove;

        /** 本帧是否跟随内容末尾。 */
        private final boolean followingTail;

        /** 是否有回合正在生成。 */
        private final boolean streaming;

        /**
         * 构造窗口。
         *
         * @param lines           可见视觉行，不可为 {@code null}
         * @param hiddenBelowRows 窗口下方的行数
         * @param hiddenAbove     窗口上方是否有内容
         * @param followingTail   是否跟随末尾
         * @param streaming       是否有回合正在生成
         */
        View(List<VisualLine> lines, int hiddenBelowRows, boolean hiddenAbove,
             boolean followingTail, boolean streaming) {
            this.lines = lines;
            this.hiddenBelowRows = hiddenBelowRows;
            this.hiddenAbove = hiddenAbove;
            this.followingTail = followingTail;
            this.streaming = streaming;
        }

        /**
         * 获取本帧可见的视觉行。
         *
         * @return 视觉行列表，保证非 {@code null}
         */
        public List<VisualLine> getLines() {
            return lines;
        }

        /**
         * 获取窗口下方未显示的行数。
         *
         * @return 行数
         */
        public int getHiddenBelowRows() {
            return hiddenBelowRows;
        }

        /**
         * 判断窗口上方是否还有内容。
         *
         * @return 有返回 {@code true}
         */
        public boolean isHiddenAbove() {
            return hiddenAbove;
        }

        /**
         * 判断本帧是否跟随末尾。
         *
         * @return 跟随返回 {@code true}
         */
        public boolean isFollowingTail() {
            return followingTail;
        }

        /**
         * 判断是否有回合正在生成。
         *
         * @return 生成中返回 {@code true}
         */
        public boolean isStreaming() {
            return streaming;
        }

        /**
         * 生成「下方还有内容」的提示文案。
         * <p>
         * 流式生成时按行数报数会变成一个不停跳动的数字（token 没有「条」的概念），
         * 因此那种情况只说「正在生成」。
         *
         * @return 提示文案；不需要提示时返回 {@code null}
         */
        public String hiddenBelowHint() {
            if (hiddenBelowRows <= 0) {
                return null;
            }
            return streaming ? "\u2193 \u6b63\u5728\u751f\u6210\u2026" : "\u2193 " + hiddenBelowRows + " \u884c";
        }
    }
}
