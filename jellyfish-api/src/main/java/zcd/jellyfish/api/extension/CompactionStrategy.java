package zcd.jellyfish.api.extension;

/**
 * 压缩策略：插件对「这一次该怎么压」的回答——<b>摘要指令正文 + 两个数量参数</b>，没有任何「删哪条消息」
 * 的决定权。
 * <p>
 * <b>压缩是插件能力，不是内核内置功能</b>：内核提供机制（读消息、选范围、发模型调用、校验摘要、推进边界、
 * 记用量、落盘），插件提供策略。因此 {@link #getSummaryPrompt()} 是这套扩展点的核心字段——
 * <b>没有任何插件给出摘要指令时，压缩功能整体不可用</b>：不会自动压，{@code /compact} 会直接告诉用户
 * 「没有插件提供压缩策略」。这不是人为设卡，而是缺件：没有指令就没有可发给模型的摘要请求。
 * <p>
 * <b>插件拿不到的东西</b>（三条边界，均为编译期或调用点约束）：
 * <ul>
 *     <li><b>拿不到消息正文</b>：{@link CompactionStrategyRequest} 的载荷只有数字与标识，插件因此无法
 *     「只压某几条」或按内容改范围；</li>
 *     <li><b>不能发起模型调用</b>：插件只回答「怎么压」，真正那次调用由内核发出，用量随之记进会话；</li>
 *     <li><b>没有否决权</b>：返回值里没有「这次不要压」这种表达。真需要时再加。</li>
 * </ul>
 * <p>
 * <b>摘要指令里应带占位符 {@code {maxSummaryChars}}</b>（形如「不超过 {maxSummaryChars} 个字符」）：
 * 内核会把它替换成当次生效的上限。占位符缺失不算错误——内核仍会在本地按上限截断兜底——只是模型少了
 * 一条自我约束，因此内核会记一条告警。用花括号而不是 {@code %d}：提示词正文里天然可能出现 {@code %}。
 * <p>
 * <b>多个插件共存时的合并规则</b>（由内核执行，逐字段、与 {@code order} 升序一致）：
 * {@code summaryPrompt} 取「order 最小且给出了非空白指令」的那一个，两个数值字段各自取「order 最小且
 * 声明了该字段」的那一个——也就是<b>逐字段按优先级取第一</b>，而不是拼接或取极值：拼接会拼出一份谁也
 * 没写过的指令，取极值在不同字段上有不同的正确方向，讲不清也难测。只声明数值、不写指令的插件是合法的
 * （它只调参数），只要另有插件提供指令即可。
 * <p>
 * <b>内核会钳制数值</b>：{@link #getKeepRecentMessages()} 落在 {@code [0, 消息总条数]}、
 * {@link #getMaxSummaryChars()} 落在内核规定的区间内。插件写出荒谬的值不该让压缩失控——
 * 这是内核对自己保命机制的把关，不是对插件的不信任。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CompactionStrategy {

    /**
     * 摘要长度上限的占位符：内核在组装摘要请求时替换成当次生效的上限值。
     * <p>
     * 常量放在这里而不是内核里，是因为它属于<b>插件与内核之间的契约</b>：插件写指令、内核做替换，
     * 两边都得认同一个字面量。
     */
    public static final String MAX_CHARS_PLACEHOLDER = "{maxSummaryChars}";

    /** 摘要指令正文，可含占位符 {@link #MAX_CHARS_PLACEHOLDER}。 */
    private final String summaryPrompt;

    /** 保留最近多少条原文不压；{@code null} 表示不表态，{@code 0} 表示一条都不留。 */
    private final Integer keepRecentMessages;

    /** 摘要长度上限（字符数）；{@code null} 表示不表态。 */
    private final Integer maxSummaryChars;

    /**
     * 构造压缩策略。
     *
     * @param summaryPrompt      摘要指令正文，可为 {@code null}
     * @param keepRecentMessages 保留最近多少条原文，可为 {@code null}
     * @param maxSummaryChars    摘要长度上限（字符数），可为 {@code null}
     */
    public CompactionStrategy(String summaryPrompt, Integer keepRecentMessages, Integer maxSummaryChars) {
        this.summaryPrompt = summaryPrompt;
        this.keepRecentMessages = keepRecentMessages;
        this.maxSummaryChars = maxSummaryChars;
    }

    /**
     * 构造「不表态」的策略：三个字段都为 {@code null}。
     * <p>
     * 用于「本插件对压缩没有意见」的场景。注意一个字段都不填的处理器不会让压缩变得可用——
     * 可用性取决于是否有插件给出了摘要指令。
     *
     * @return 三个字段都为 {@code null} 的策略
     */
    public static CompactionStrategy none() {
        return new CompactionStrategy(null, null, null);
    }

    /**
     * 获取摘要指令正文。
     *
     * @return 指令正文，可能为 {@code null} 或空白
     */
    public String getSummaryPrompt() {
        return summaryPrompt;
    }

    /**
     * 获取保留最近多少条原文。
     *
     * @return 条数，可能为 {@code null}
     */
    public Integer getKeepRecentMessages() {
        return keepRecentMessages;
    }

    /**
     * 获取摘要长度上限。
     *
     * @return 字符数，可能为 {@code null}
     */
    public Integer getMaxSummaryChars() {
        return maxSummaryChars;
    }

    @Override
    public String toString() {
        return "CompactionStrategy{keepRecentMessages=" + keepRecentMessages
                + ", maxSummaryChars=" + maxSummaryChars
                + ", summaryPromptLength=" + (summaryPrompt == null ? 0 : summaryPrompt.length()) + '}';
    }
}
