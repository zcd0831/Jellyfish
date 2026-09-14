package zcd.jellyfish.tui;

import java.util.Objects;

/**
 * 进行中回合的暂存区：装「还没成为会话消息」的流式增量。
 * <p>
 * <b>为什么需要它（T4 的唯一例外）</b>：会话是按<b>轮</b>落库的，不是按 token——{@code ReActLooper}
 * 只在「每轮模型响应聚合完成后」才调 {@code appendMessage(assistant)}。因此在流式进行中，
 * 当前轮的增量文本在 {@code Session} 里根本不存在。视图要显示它，就必须有个地方暂存。
 * <p>
 * <b>为什么不违反 T4「视图 = 会话投影」</b>：T4 禁的是复制一份<b>已有</b>的会话消息列表——
 * 那会带来缓存失效类 bug。而本类装的是「尚未成为会话消息」的内容，它在 {@code Session} 里
 * <b>不存在</b>，没有第二权威可言。回合终结即清空，生命周期与回合严格对齐。
 * <p>
 * <b>工具轨迹为什么不在本类里</b>：{@code ReActLooper.executeTool} 里 {@code onToolCallStarted}
 * 发生在 assistant 消息落库<b>之后</b>，所以工具调用（{@code assistant.toolCalls}）与其结果
 * （{@code tool} 消息）都已经在 {@code Session} 里了——轨迹直接由会话投影得出，不需要第二份。
 * 这种「能投影就不缓存」的取舍同样是在守 T4。
 * <p>
 * <b>线程契约</b>：{@link #appendThinking}/{@link #appendText}/{@link #clearText}/{@link #finish}
 * 只由 {@code react} 线程调用；{@link #snapshot} 只由渲染线程调用。两侧用实例锁交接，
 * 另有一个 volatile 脏标记让渲染线程在「无变化」时省掉投影开销。
 * <p>
 * <b>为什么有界</b>：注入的模型输出长度不受我们控制（模型可能一次吐几十万字符，或陷入重复循环）。
 * 无界缓冲会把内存吃光，而流式期间每秒几十次追加让这种失控增长很快。因此超过上限时
 * <b>保留最新的部分</b>并置截断标记：流式期间用户的注意力在末尾，配合「跟随底部」的滚动策略，
 * 保留尾部比保留开头更符合实际阅读行为。
 *
 * @author zcd
 */
public final class InflightTurn {

    /** 单回合正文上限（字符数）。超出后保留尾部并标记截断。 */
    static final int MAX_TEXT_CHARS = 256 * 1024;

    /** 单回合思考过程上限（字符数）。思考只用于展示，上限比正文更小。 */
    static final int MAX_THINKING_CHARS = 64 * 1024;

    /** 回合终局。 */
    public enum Outcome {

        /** 尚未开始任何回合：开场状态。
         * <p>
         * 与 {@link #RUNNING} 分开是必要的：若开场就用 {@code RUNNING}，屏幕会在用户还没发第一条消息时
         * 就显示一个「处理中…」提示——那是假的。 */
        IDLE,

        /** 回合进行中。 */
        RUNNING,

        /** 正常收敛。 */
        COMPLETED,

        /** 达到最大轮次仍未收敛。 */
        TRUNCATED,

        /** 用户中断。 */
        CANCELLED,

        /** 失败。 */
        ERROR
    }

    /** 回合终局。开场为 {@link Outcome#IDLE}。 */
    private Outcome outcome = Outcome.IDLE;

    /** 失败原因，仅 {@link Outcome#ERROR} 时非空。 */
    private String errorMessage;

    /** 当前轮正文增量。 */
    private final StringBuilder text = new StringBuilder();

    /** 当前轮思考过程增量。 */
    private final StringBuilder thinking = new StringBuilder();

    /** 正文是否因超限被截断。 */
    private boolean textTruncated;

    /** 思考过程是否因超限被截断。 */
    private boolean thinkingTruncated;

    /** 是否有尚未被渲染线程取走的变更。 */
    private volatile boolean dirty;

    /**
     * 开始一个新回合：清空正文与思考，并把终局重置为进行中。
     * <p>
     * <b>为什么必须显式调用</b>：上一回合结束时终局停在 {@code COMPLETED} / {@code ERROR}，
     * 若不重置，新回合的流式正文会被投影器当成「已结束回合」而不显示——
     * 表现为用户发完消息后屏幕毫无反应。
     * <p>
     * 调用点在渲染线程、且早于任何回回调，因此与 {@code react} 线程的写不构成竞争。
     */
    public void begin() {
        synchronized (this) {
            text.setLength(0);
            thinking.setLength(0);
            textTruncated = false;
            thinkingTruncated = false;
            outcome = Outcome.RUNNING;
            errorMessage = null;
            dirty = true;
        }
    }

    /**
     * 追加思考过程增量。
     *
     * @param delta 增量文本，{@code null} 或空串忽略
     */
    public void appendThinking(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            thinkingTruncated |= appendBounded(thinking, delta, MAX_THINKING_CHARS);
            dirty = true;
        }
    }

    /**
     * 追加正文增量。
     *
     * @param delta 增量文本，{@code null} 或空串忽略
     */
    public void appendText(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            textTruncated |= appendBounded(text, delta, MAX_TEXT_CHARS);
            dirty = true;
        }
    }

    /**
     * 清空当前轮正文与思考过程，但保留回合终局。
     * <p>
     * 调用时机是「该轮已经落库」的那一刻：工具即将开始执行（{@code onToolCallStarted}）或回合终结。
     * 清空必须<b>晚于</b>落库，否则屏幕上会出现「正文闪一下就不见」——
     * {@code ReActLooper} 先 {@code appendMessage} 再回调，顺序天然安全，本类只需守住自己的方向。
     */
    public void clearText() {
        synchronized (this) {
            if (text.length() == 0 && thinking.length() == 0) {
                return;
            }
            text.setLength(0);
            thinking.setLength(0);
            dirty = true;
        }
    }

    /**
     * 标记回合终结。
     *
     * @param outcome      终局，不可为 {@code null}
     * @param errorMessage 失败原因，仅 {@link Outcome#ERROR} 时有意义，可为 {@code null}
     */
    public void finish(Outcome outcome, String errorMessage) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        synchronized (this) {
            this.outcome = outcome;
            this.errorMessage = errorMessage;
            dirty = true;
        }
    }

    /**
     * 判断是否有未被渲染线程取走的变更。
     *
     * @return 有变更返回 {@code true}
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 标记变更已被取走。
     * <p>
     * 由渲染线程在完成投影后调用。脏标记只是性能优化，不影响正确性：
     * 即使这里与下一次 {@code append} 竞争，最坏结果是多投影一帧。
     */
    public void clearDirty() {
        dirty = false;
    }

    /**
     * 取一份不可变快照，供渲染线程构建界面。
     *
     * @return 快照，保证非 {@code null}
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(outcome, errorMessage, text.toString(), thinking.toString(),
                textTruncated, thinkingTruncated);
    }

    /**
     * 判断回合是否仍在进行中。
     *
     * @return 进行中返回 {@code true}
     */
    public synchronized boolean isRunning() {
        return outcome == Outcome.RUNNING;
    }

    /**
     * 追加文本并在超限时保留尾部。
     *
     * @param target 目标缓冲
     * @param delta  增量
     * @param max    上限字符数
     * @return 本次是否发生了截断
     */
    private static boolean appendBounded(StringBuilder target, String delta, int max) {
        target.append(delta);
        if (target.length() <= max) {
            return false;
        }
        // 保留最新的 max 个字符：流式期间用户看的是末尾
        target.delete(0, target.length() - max);
        return true;
    }

    /**
     * 暂存区的不可变快照。
     * <p>
     * 存在的意义是把「读」与「写」分开：渲染一帧要读多个字段，逐个加锁既啰嗦又可能读到中间状态；
     * 换成一次取快照，渲染线程拿到的就是一致的一份。
     */
    public static final class Snapshot {

        /** 回合终局。 */
        private final Outcome outcome;

        /** 失败原因，可为 {@code null}。 */
        private final String errorMessage;

        /** 正文文本，保证非 {@code null}。 */
        private final String text;

        /** 思考过程文本，保证非 {@code null}。 */
        private final String thinking;

        /** 正文是否被截断。 */
        private final boolean textTruncated;

        /** 思考过程是否被截断。 */
        private final boolean thinkingTruncated;

        /**
         * 构造快照。
         *
         * @param outcome           回合终局
         * @param errorMessage      失败原因，可为 {@code null}
         * @param text              正文文本
         * @param thinking          思考过程文本
         * @param textTruncated     正文是否被截断
         * @param thinkingTruncated 思考过程是否被截断
         */
        Snapshot(Outcome outcome, String errorMessage, String text, String thinking,
                 boolean textTruncated, boolean thinkingTruncated) {
            this.outcome = outcome;
            this.errorMessage = errorMessage;
            this.text = text;
            this.thinking = thinking;
            this.textTruncated = textTruncated;
            this.thinkingTruncated = thinkingTruncated;
        }

        /**
         * 获取回合终局。
         *
         * @return 终局，保证非 {@code null}
         */
        public Outcome getOutcome() {
            return outcome;
        }

        /**
         * 获取失败原因。
         *
         * @return 失败原因，无则为 {@code null}
         */
        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * 获取正文文本。
         *
         * @return 正文文本，保证非 {@code null}
         */
        public String getText() {
            return text;
        }

        /**
         * 获取思考过程文本。
         *
         * @return 思考过程文本，保证非 {@code null}
         */
        public String getThinking() {
            return thinking;
        }

        /**
         * 判断正文是否被截断。
         *
         * @return 被截断返回 {@code true}
         */
        public boolean isTextTruncated() {
            return textTruncated;
        }

        /**
         * 判断思考过程是否被截断。
         *
         * @return 被截断返回 {@code true}
         */
        public boolean isThinkingTruncated() {
            return thinkingTruncated;
        }

        /**
         * 判断快照是否没有任何可显示内容。
         *
         * @return 无内容返回 {@code true}
         */
        public boolean isEmpty() {
            boolean noContent = text.isEmpty() && thinking.isEmpty();
            return noContent && (outcome == Outcome.RUNNING || outcome == Outcome.IDLE);
        }
    }
}
