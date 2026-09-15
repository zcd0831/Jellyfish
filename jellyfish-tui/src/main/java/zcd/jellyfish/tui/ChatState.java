package zcd.jellyfish.tui;

import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** 当前进行中的回合句柄，无回合时为 {@code null}。 */
    private ReActTurn currentTurn;

    /** 最近一次会话标识，用于识别会话切换。 */
    private String lastSessionId;

    /** 最近一次会话消息条数。 */
    private int lastMessageCount = -1;

    /** 最近一条消息的标识，用于识别「条数没变但内容变了」的替换场景。 */
    private String lastMessageId;

    /** 最近一次投影的列数，-1 表示尚未投影过。 */
    private int lastWidth = -1;

    /** 最近一次投影使用的消息上限。 */
    private int lastMaxMessages = -1;

    /** 最近一次投影时的提示版本号。 */
    private int lastNoticeVersion = -1;

    /** 最近一次投影的结果，作为「什么都没变」时的复用对象。 */
    private List<VisualLine> projected = Collections.emptyList();

    /** 外壳提示缓冲（命令结果等），参与投影时按时间戳与会话消息归并。 */
    private final List<ShellNotice> notices = new ArrayList<ShellNotice>();

    /** 提示缓冲版本号，参与投影缓存判据。 */
    private int noticeVersion;

    /** 提示条数上限：它是附属于界面的反馈，不应无界增长。 */
    static final int MAX_NOTICES = 50;

    /**
     * 追加一条带命令原文的外壳提示（命令结果）。
     * <p>
     * 只由渲染线程调用。提示不进会话（见 {@link ShellNotice}），但带自己的时间戳参与投影：
     * 它因此出现在实际发生的时刻上，而不是永远贴在屏幕底部。
     *
     * @param command 触发本提示的命令原文，可为 {@code null} 或空白（不回显命令）
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
     * 获取暂存区，供 {@link TuiReActListener} 写入。
     *
     * @return 暂存区，保证非 {@code null}
     */
    public InflightTurn getInflight() {
        return inflight;
    }

    /**
     * 开始一个新回合：重置暂存区并绑定回合句柄。
     * <p>
     * 暂存区的重置必须发生在这里（而不是在某个回调里）：回调开始时模型可能还没吐出任何字符，
     * 那时已经是 {@code RUNNING}，但上一回合的终局若不先清掉，投影器会把它当成已结束回合。
     *
     * @param turn 回合句柄，可为 {@code null}
     */
    public void beginTurn(ReActTurn turn) {
        inflight.begin();
        this.currentTurn = turn;
    }

    /**
     * 绑定当前进行中的回合，供 {@code Esc} 中断。
     *
     * @param turn 回合句柄，可为 {@code null}（表示清空）
     */
    public void bindTurn(ReActTurn turn) {
        this.currentTurn = turn;
    }

    /**
     * 判断当前是否有进行中的回合。
     *
     * @return 有进行中回合返回 {@code true}
     */
    public boolean isTurnRunning() {
        return inflight.isRunning();
    }

    /**
     * 中断当前回合。
     * <p>
     * 中断必须由渲染线程主动调用（而不是等 {@code react} 线程投递消息），否则用户按下 {@code Esc}
     * 之后界面上不会有任何立刻可见的反应——那与「卡死」无法区分。句柄为空或回合已结束时什么都不做，
     * 因此重复按 {@code Esc} 是安全的。
     */
    public void cancelTurn() {
        ReActTurn turn = currentTurn;
        if (turn != null) {
            turn.cancel();
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
     * @return 本帧窗口，保证非 {@code null}
     */
    public View view(String sessionId, List<SessionMessage> messages, int width, int viewportRows, int maxMessages) {
        refreshProjection(sessionId, messages, width, maxMessages);
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
     * @param sessionId   会话标识
     * @param messages    消息列表
     * @param width       可用列数
     * @param maxMessages 消息条数上限
     */
    private void refreshProjection(String sessionId, List<SessionMessage> messages, int width, int maxMessages) {
        List<SessionMessage> source = messages == null ? Collections.<SessionMessage>emptyList() : messages;
        String lastId = source.isEmpty() ? null : source.get(source.size() - 1).getMessageId();
        boolean unchanged = lastWidth == width
                && lastMaxMessages == maxMessages
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
            projected = TranscriptProjector.home(notices, width);
        } else {
            projected = TranscriptProjector.project(source, notices, snapshot, width, maxMessages);
        }
        lastWidth = width;
        lastMaxMessages = maxMessages;
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
