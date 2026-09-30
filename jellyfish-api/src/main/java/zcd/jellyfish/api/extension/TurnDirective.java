package zcd.jellyfish.api.extension;

/**
 * 回合指令：插件在 {@link TurnBeforeRequest} 上能表达的结果。
 * <p>
 * 三态：放行、拦下这个回合、换掉输入文本。
 * <p>
 * <b>为什么可以换输入文本</b>：它覆盖的是「用户刚敲下的这一句」，典型用法是补上下文
 * （把当前分支、当前目录、最近的报错拼进去）。这与提示词注入（{@link PromptContribution}）不同：
 * 后者是稳定前缀的一部分、每轮都在，前者只影响这一次输入，因此不会破坏缓存前缀。
 * <p>
 * <b>只对顶层回合生效</b>：嵌套回合的输入是模型写出来的任务描述，改写它会让
 * 「模型要什么」与「子代理收到什么」分叉，而模型无从得知。内核在嵌套回合上会忽略本指令
 * 并记 DEBUG，请求里的 {@link TurnBeforeRequest#isNested()} 让插件可以提前判断。
 * <p>
 * <b>拦下不产生任何消息</b>：不追加用户消息、不调用模型、不伪造 assistant 消息——伪造会污染历史。
 * 理由原样带到外壳展示。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnDirective {

    /** 单例：放行。 */
    private static final TurnDirective PROCEED = new TurnDirective(false, null, null);

    /** 是否拦下本回合。 */
    private final boolean cancelled;

    /** 拦下的理由，可为 {@code null}。 */
    private final String reason;

    /** 替换后的输入，{@code null} 表示不改。 */
    private final String input;

    /**
     * 构造指令。
     *
     * @param cancelled 是否拦下
     * @param reason    拦下的理由，可为 {@code null}
     * @param input     替换后的输入，可为 {@code null}（表示不改）
     */
    public TurnDirective(boolean cancelled, String reason, String input) {
        this.cancelled = cancelled;
        this.reason = reason;
        this.input = input;
    }

    /**
     * 构造「放行」指令。
     *
     * @return 指令
     */
    public static TurnDirective proceed() {
        return PROCEED;
    }

    /**
     * 构造「拦下本回合」指令。
     *
     * @param reason 拦下的理由，可为 {@code null}
     * @return 指令
     */
    public static TurnDirective cancel(String reason) {
        return new TurnDirective(true, reason, null);
    }

    /**
     * 构造「替换输入」指令。
     * <p>
     * 它与「拦下」互斥：拦下了就没有输入可言。传 {@code null} 等价于放行——用 {@code null}
     * 同时表示「不改」与「改成空」会让调用点的判断无法解释。
     *
     * @param input 替换后的输入，可为 {@code null}（表示不改）
     * @return 指令
     */
    public static TurnDirective replaceInput(String input) {
        return input == null ? PROCEED : new TurnDirective(false, null, input);
    }

    /**
     * 判断是否拦下。
     *
     * @return 拦下返回 {@code true}
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 获取拦下的理由。
     *
     * @return 理由，未拦下时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 判断是否要替换输入。
     *
     * @return 要替换返回 {@code true}
     */
    public boolean hasInput() {
        return !cancelled && input != null;
    }

    /**
     * 获取替换后的输入。
     *
     * @return 替换后的输入，未指定时为 {@code null}
     */
    public String getInput() {
        return input;
    }

    @Override
    public String toString() {
        return "TurnDirective{cancelled=" + cancelled + ", reason=" + reason + '}';
    }
}
