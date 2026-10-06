package zcd.jellyfish.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>
 * <b>运行中的工具轨迹为什么也放在这里</b>：工具结果（{@code tool} 消息）要等工具<b>返回之后</b>
 * 才落库，而命令行的输出是在它返回<b>之前</b>持续产生的。这段时间是屏幕上唯一「什么都看不到」的
 * 窗口（会话里没有、暂存区里也没有），而它可能长达几分钟。因此实时输出必须暂存在这里。
 * <p>
 * 它是暂存区的第二个例外，但理由与第一个相同：暂存的是<b>尚未成为会话消息</b>的内容。
 * 工具一返回、结果落库，这份内容就被清掉，权威文本只剩下会话里的那一份。
 * <p>
 * <b>工具轨迹为什么比正文更小</b>：它只服务于「让用户知道命令还在干活」。正文丢掉后用户就看不到
 * 回答本身，而这里丢掉的内容在工具返回时会被权威结果取代。因此它留末 {@value #MAX_TOOL_OUTPUT_LINES} 行、
 * 每行 {@value #MAX_TOOL_OUTPUT_LINE_CHARS} 字符，且<b>不标「已折叠」</b>——那块标记会被误读成
 * 「工具结果被截断了」，而真正可能截断结果的是内核的截断中间件，它有自己的一套标识。
 * <p>
 * <b>线程契约（本节与其他部分不同）</b>：{@code appendText}/{@code appendThinking} 只由 {@code react}
 * 线程调用，但 {@link #appendToolOutput(String)} 由<b>工具自己的泵线程</b>调用，stdout 与 stderr 两条
 * 线程会<b>并发</b>进来，因此它比其余方法更需要那个实例锁（外面还会再有一层显示层过滤）。
 *
 * @author zcd
 */
public final class InflightTurn {

    /** 单回合正文上限（字符数）。超出后保留尾部并标记截断。 */
    static final int MAX_TEXT_CHARS = 256 * 1024;

    /** 单回合思考过程上限（字符数）。思考只用于展示，上限比正文更小。 */
    static final int MAX_THINKING_CHARS = 64 * 1024;

    /** 运行中工具轨迹保留的行数上限（保留最新的这些行）。 */
    static final int MAX_TOOL_OUTPUT_LINES = 20;

    /** 运行中工具轨迹单行的字符上限。 */
    static final int MAX_TOOL_OUTPUT_LINE_CHARS = 200;

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

        /**
         * 被插件在回合开始前拦下。
         * <p>
         * 与 {@link #CANCELLED} 分开：用户没按过 Esc，这不是用户意图；也与 {@link #ERROR} 分开：
         * 没有失败，只是一次策略拦截（{@code -cli} 为此给了独立的退出码）。
         */
        BLOCKED,

        /** 失败。 */
        ERROR
    }

    /** 回合终局。开场为 {@link Outcome#IDLE}。 */
    private Outcome outcome = Outcome.IDLE;

    /**
     * 终局说明，三种终局各有用途：
     * <ul>
     *     <li>{@link Outcome#ERROR}：失败原因；</li>
     *     <li>{@link Outcome#BLOCKED}：拦下的理由；</li>
     *     <li>{@link Outcome#COMPLETED}：内核补的提示（回复被输出上限截断、模型一个字都没回）。</li>
     * </ul>
     * <b>为什么不叫 errorMessage</b>：它早就不是「错误」专属了——被拦下不是错误，截断提示更不是。
     * 名字与含义对不上时，下一个人会把「不是错误」的那两种当成错误来处理（例如染成红色）。
     */
    private String note;

    /** 当前轮正文增量。 */
    private final StringBuilder text = new StringBuilder();

    /** 当前轮思考过程增量。 */
    private final StringBuilder thinking = new StringBuilder();

    /**
     * 已完整的工具输出行（每行都见过一个换行），超出上限时保留最新的若干行。
     * <p>
     * 用行而不是单个大缓冲：行的边界是换行，而换行只有工具自己知道；先在这里切好，
     * 渲染层就不必再对一段可能从行中间开始的文本做切行。
     */
    private final Deque<String> toolOutputLines = new ArrayDeque<String>();

    /**
     * 工具的「尚未换行的当前行」。
     * <p>
     * 与 {@link #toolOutputLines} 分开是必要的：命令的最后一行往往不带换行，而一个以换行结尾的
     * 片段也不该凭空多出一条空行。分成「已完整的行 + 当前行」之后，两种情形都不需要特判。
     */
    private final StringBuilder toolCurrentLine = new StringBuilder();

    /** 正在执行的工具名，不在执行中时为 {@code null}。 */
    private String runningToolName;

    /**
     * 正在执行的工具参数，不在执行中或没有参数时为空映射。
     * <p>
     * 参数来自模型 / 内核，引用进来就必须做一份不可变拷贝——与 {@code ToolCallRequest} 的同一处理。
     * 它只服务于「工具返回之前」的展示，工具一返回就随 {@link #clearToolOutput()} 一起清掉，
     * 不留第二权威。
     */
    private Map<String, Object> runningToolArguments = Collections.emptyMap();

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
            toolOutputLines.clear();
            toolCurrentLine.setLength(0);
            runningToolName = null;
            runningToolArguments = Collections.emptyMap();
            textTruncated = false;
            thinkingTruncated = false;
            outcome = Outcome.RUNNING;
            note = null;
            dirty = true;
        }
    }

    /**
     * 记录一个工具开始执行，并清空上一个工具的实时输出。
     * <p>
     * 名字先于输出生效：一条要跑几十秒、什么都不输出的命令，屏幕上至少得先出现它的名字，
     * 否则那段时间与「卡死了」看不出区别。
     * <p>
     * 本重载不携带参数，等价于 {@link #beginTool(String, Map)} 传 {@code null}；
     * 既有的二参回调调用点靠它保持行为不变。
     *
     * @param toolName 工具名，可为 {@code null}
     */
    public void beginTool(String toolName) {
        beginTool(toolName, null);
    }

    /**
     * 记录一个工具开始执行（带参数），并清空上一个工具的实时输出。
     * <p>
     * <b>参数为什么要存一份</b>：工具结果要等它返回后才落库，而「这条工具在动哪个文件 / 哪个目标」
     * 是运行中那段窗口里用户唯一能看到的信息（命令可能跑几分钟）。参数副本只用于展示，
     * 工具一返回即随 {@link #clearToolOutput()} 清掉。
     *
     * @param toolName  工具名，可为 {@code null}
     * @param arguments 工具参数，可为 {@code null}（等价空参数）；会被防御性拷贝
     */
    public void beginTool(String toolName, Map<String, Object> arguments) {
        synchronized (this) {
            runningToolName = toolName;
            runningToolArguments = copyArguments(arguments);
            toolOutputLines.clear();
            toolCurrentLine.setLength(0);
            dirty = true;
        }
    }

    /**
     * 追加一段工具执行期的输出。
     * <p>
     * <b>可能被多条线程并发调用</b>（stdout / stderr 各一条泵线程），因此整体在实例锁内完成。
     * 超出行数或行内字符上限的部分直接丢掉：这是显示层的暂存，权威文本由工具返回后的截断中间件负责。
     *
     * @param chunk 输出片段，{@code null} 或空串忽略；可能不含换行、可能不是一个完整的行
     */
    public void appendToolOutput(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        synchronized (this) {
            appendChunk(chunk);
            dirty = true;
        }
    }

    /**
     * 清空运行中工具的名字与输出。
     * <p>
     * 调用时机是工具<b>返回</b>的那一刻：结果随后就会落库，再由会话投影渲染成正式的工具轨迹。
     * 留着不清会让同一件事在屏幕上出现两份（一份实时、一份落库后）。
     */
    public void clearToolOutput() {
        synchronized (this) {
            runningToolName = null;
            // 必须在提前 return 之前清：一次没有任何输出的工具调用也会走这个分支
            runningToolArguments = Collections.emptyMap();
            if (toolOutputLines.isEmpty() && toolCurrentLine.length() == 0) {
                return;
            }
            toolOutputLines.clear();
            toolCurrentLine.setLength(0);
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
     * @param outcome 终局，不可为 {@code null}
     * @param note    终局说明，用途见 {@link #note}，可为 {@code null}
     */
    public void finish(Outcome outcome, String note) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        synchronized (this) {
            this.outcome = outcome;
            this.note = note;
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
        return new Snapshot(outcome, note, text.toString(), thinking.toString(),
                textTruncated, thinkingTruncated, runningToolName, runningToolArguments, toolLines());
    }

    /**
     * 拷贝一份工具参数快照：映射来自模型 / 内核，引用进来就必须复制，
     * 并且对外只暴露不可变视图（与 {@code ToolCallRequest} 的同一处理）。
     *
     * @param arguments 工具参数，可为 {@code null}
     * @return 不可变映射，保证非 {@code null}
     */
    private static Map<String, Object> copyArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
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
     * 取当前工具的完整行列表：已完整的行 + 尚未换行的当前行，并保留最新的若干行。
     * <p>
     * 行数上限在这里统一施加（而不是只在追加时），否则「当前行」会成为上限之外的额外一行——
     * 一个长期只输出不带换行内容的命令能让它无限增长下去。
     *
     * @return 行列表，保证非 {@code null}
     */
    private List<String> toolLines() {
        List<String> lines = new ArrayList<String>(toolOutputLines.size() + 1);
        lines.addAll(toolOutputLines);
        if (toolCurrentLine.length() > 0) {
            lines.add(toolCurrentLine.toString());
        }
        if (lines.size() > MAX_TOOL_OUTPUT_LINES) {
            return new ArrayList<String>(lines.subList(lines.size() - MAX_TOOL_OUTPUT_LINES, lines.size()));
        }
        return lines;
    }

    /**
     * 把一段输出按换行拆成行追加进缓冲，并在超行数时保留最新的那几行。
     *
     * @param chunk 输出片段
     */
    private void appendChunk(String chunk) {
        int start = 0;
        while (true) {
            int newline = chunk.indexOf('\n', start);
            boolean last = newline < 0;
            appendSegment(last ? chunk.substring(start) : chunk.substring(start, newline));
            if (last) {
                break;
            }
            // 换行：当前行到此完整（空串也是一条完整的空行），收进行缓冲再开新的当前行
            toolOutputLines.addLast(toolCurrentLine.toString());
            toolCurrentLine.setLength(0);
            start = newline + 1;
        }
        while (toolOutputLines.size() > MAX_TOOL_OUTPUT_LINES) {
            toolOutputLines.removeFirst();
        }
    }

    /**
     * 把一段不带换行的文本追加到当前行。
     *
     * @param segment 文本片段，可为空串
     */
    private void appendSegment(String segment) {
        if (toolCurrentLine.length() >= MAX_TOOL_OUTPUT_LINE_CHARS) {
            // 这一行已经满了：本段的字符属于「显示层溢出」，丢掉即可，工具返回后仍有权威文本
            return;
        }
        int room = MAX_TOOL_OUTPUT_LINE_CHARS - toolCurrentLine.length();
        toolCurrentLine.append(segment, 0, Math.min(room, segment.length()));
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

        /** 终局说明（ERROR 的原因 / BLOCKED 的理由 / COMPLETED 的提示），可为 {@code null}。 */
        private final String note;

        /** 正文文本，保证非 {@code null}。 */
        private final String text;

        /** 思考过程文本，保证非 {@code null}。 */
        private final String thinking;

        /** 正文是否被截断。 */
        private final boolean textTruncated;

        /** 思考过程是否被截断。 */
        private final boolean thinkingTruncated;

        /** 正在执行的工具名，不在执行中时为 {@code null}。 */
        private final String runningToolName;

        /** 正在执行的工具参数（不可变），没有参数时为空映射，保证非 {@code null}。 */
        private final Map<String, Object> runningToolArguments;

        /** 运行中工具的输出行（保留最新的若干行），保证非 {@code null}。 */
        private final List<String> toolOutputLines;

        /**
         * 构造快照。
         *
         * @param outcome           回合终局
         * @param note              终局说明，可为 {@code null}
         * @param text              正文文本
         * @param thinking          思考过程文本
         * @param textTruncated     正文是否被截断
         * @param thinkingTruncated 思考过程是否被截断
         * @param runningToolName   正在执行的工具名，可为 {@code null}
         * @param toolOutputLines   运行中工具的输出行
         */
        Snapshot(Outcome outcome, String note, String text, String thinking,
                 boolean textTruncated, boolean thinkingTruncated, String runningToolName,
                 Map<String, Object> runningToolArguments, List<String> toolOutputLines) {
            this.outcome = outcome;
            this.note = note;
            this.text = text;
            this.thinking = thinking;
            this.textTruncated = textTruncated;
            this.thinkingTruncated = thinkingTruncated;
            this.runningToolName = runningToolName;
            this.runningToolArguments = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(runningToolArguments));
            this.toolOutputLines = Collections.unmodifiableList(toolOutputLines);
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
         * 获取终局说明。
         * <p>
         * 三种终局各有用途，见 {@link InflightTurn#note}。<b>不要假定它只在失败时才有值</b>：
         * 收敛时它承载的是内核的提示（回复被输出上限截断、模型一个字都没回）。
         *
         * @return 终局说明，无则为 {@code null}
         */
        public String getNote() {
            return note;
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
            boolean noContent = text.isEmpty() && thinking.isEmpty()
                    && runningToolName == null && toolOutputLines.isEmpty();
            return noContent && (outcome == Outcome.RUNNING || outcome == Outcome.IDLE);
        }

        /**
         * 获取正在执行的工具名。
         *
         * @return 工具名，不在执行中则为 {@code null}
         */
        public String getRunningToolName() {
            return runningToolName;
        }

        /**
         * 获取正在执行的工具参数。
         *
         * @return 不可变参数映射，没有参数时为空映射，保证非 {@code null}
         */
        public Map<String, Object> getRunningToolArguments() {
            return runningToolArguments;
        }

        /**
         * 获取运行中工具的输出行。
         *
         * @return 输出行（保留最新的若干行），保证非 {@code null}
         */
        public List<String> getToolOutputLines() {
            return toolOutputLines;
        }
    }
}
