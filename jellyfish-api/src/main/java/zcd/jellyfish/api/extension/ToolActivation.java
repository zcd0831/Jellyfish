package zcd.jellyfish.api.extension;

/**
 * 工具激活判定结果：这个工具<b>该不该出现在本次清单里</b>。
 * <p>
 * <b>三态而不是布尔</b>：{@code ABSTAIN} 表示「与我无关」，好让后续插件继续表态；
 * 布尔会让「不想管」与「明确要它可见」变成同一个值，于是一个只想隐藏某个工具的插件
 * 会被前一个插件的「不管」冲掉，或者反过来——取决于遍历顺序。这与
 * {@link PermissionVerdict} 的三态是同一个理由。
 * <p>
 * <b>只有「可见」与「隐藏」，没有「延迟加载」</b>：deferred loading 需要厂商协议支持
 * （Anthropic 的 {@code defer_loading}、OpenAI 的 {@code tool_search}），而内核的
 * {@code LlmRequest} 今天是厂商无关的扁平 {@code tools} 列表——表达不了「先别下发、用到再取」。
 * 等 provider 层能表达「这个端点支持延迟加载」之后再评估。
 * <p>
 * <b>「隐藏」不是「禁用」</b>：被隐藏的工具<b>不进下发给模型的那份清单</b>，但它<b>仍在注册表里</b>
 * （描述符查得到），{@code ToolExecutor} 直接调用它仍然会执行——清单是「建议」，权限才是「约束」。
 * 这条与 {@link zcd.jellyfish.api.extension.ToolDescriptor} 的执行期准入完全同构。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolActivation {

    /** 判定。 */
    private final boolean hidden;

    /** 是否表了态。 */
    private final boolean decided;

    /** 隐藏的理由，仅用于诊断。 */
    private final String reason;

    /**
     * 构造判定结果。
     *
     * @param decided 是否表了态
     * @param hidden  是否隐藏
     * @param reason  理由，可为 {@code null}
     */
    private ToolActivation(boolean decided, boolean hidden, String reason) {
        this.decided = decided;
        this.hidden = hidden;
        this.reason = reason;
    }

    /**
     * 构造「与我无关」的判定，让后续插件继续表态。
     *
     * @return 判定
     */
    public static ToolActivation abstain() {
        return new ToolActivation(false, false, null);
    }

    /**
     * 构造「这个工具应当可见」的判定。
     * <p>
     * 它与 {@link #abstain()} 的区别是<b>它能压过后面的插件</b>：合并规则是「第一个表了态的胜出」，
     * 因此一个插件用 {@code visible()} 表达的是「这个工具在我的场景里必须有」。
     *
     * @return 判定
     */
    public static ToolActivation visible() {
        return new ToolActivation(true, false, null);
    }

    /**
     * 构造「这个工具不该出现在清单里」的判定。
     *
     * @param reason 理由，用于诊断输出（例如「本会话未连接对应的服务」），可为 {@code null}
     * @return 判定
     */
    public static ToolActivation hidden(String reason) {
        return new ToolActivation(true, true, reason);
    }

    /**
     * 判断插件是否表了态。
     *
     * @return 表了态返回 {@code true}
     */
    public boolean isDecided() {
        return decided;
    }

    /**
     * 判断是否要求隐藏该工具。
     *
     * @return 要求隐藏返回 {@code true}
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * 获取隐藏的理由。
     *
     * @return 理由，未提供或未要求隐藏时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "ToolActivation{" + (decided ? (hidden ? "hidden" : "visible") : "abstain")
                + (reason == null ? "" : ", reason=" + reason) + '}';
    }
}
