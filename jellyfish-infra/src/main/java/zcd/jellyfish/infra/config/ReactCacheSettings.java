package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code jellyfish.json} 的 {@code react.cache} 段：提示词缓存的治理参数。
 * <p>
 * <b>为什么单独成段，而不是并进 {@code react} 或 {@code react.toolOutput}</b>：这些旋钮的取舍方向
 * 与「省不省 token」是反的。厂商的 prompt 缓存是前缀匹配、命中部分按约 0.1× 计价，因此一个改动
 * 即使**减少**了 token，也可能让它后面的整段从 0.1× 变回 1×——净亏。这类「花小钱省大钱还是相反」
 * 的权衡必须与其它旋钮分开看，混在一起会让人以为「调小就等于调省」。
 * <p>
 * 目前只有一项：
 * <ul>
 *     <li>{@code agingPercent}：工具结果老化的触发水位线。{@code 0}（缺省）沿用旧口径——
 *     按「距尾部多少条消息」每轮重算，边界随尾部滑动；{@code > 0} 则只在上下文用量达到该百分比时
 *     老化，且**一个压缩周期内只推进一次**，其余轮次的边界冻住不动，从而让已发过的前缀保持
 *     append-only。推导见 {@code docs/design/llm-cache.md} 的 R2 与 P3。</li>
 * </ul>
 * 非法值（负数、超过 100）回退到缺省值：配置问题不阻断启动是本仓库的既有口径，真正的行为约束在
 * 运行期兜底。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class ReactCacheSettings {

    /**
     * 老化触发水位的缺省值。
     * <p>
     * {@code 0} 表示<strong>沿用旧口径</strong>而不是「关闭老化」：这样升级本版本不会改变任何既有
     * 行为。要改用新的水位触发，写一个 {@code (0, 100]} 的值；要彻底关掉老化，
     * 把 {@code react.toolOutput.keepRecentMessages} 写成 {@code 0}。
     */
    public static final int DEFAULT_AGING_PERCENT = 0;

    /** 工具结果老化的触发水位线，{@code 0} 表示沿用旧口径。 */
    private final int agingPercent;

    /**
     * 构造缺省缓存设置。
     */
    public ReactCacheSettings() {
        this(null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param agingPercent 老化触发水位线（百分比）；{@code 0} 合法（沿用旧口径），
     *                     负数、超过 100 或缺省按缺省值处理
     */
    @JsonCreator
    public ReactCacheSettings(@JsonProperty("agingPercent") Integer agingPercent) {
        this.agingPercent = agingPercent != null && agingPercent >= 0 && agingPercent <= 100
                ? agingPercent : DEFAULT_AGING_PERCENT;
    }

    /**
     * 获取工具结果老化的触发水位线。
     *
     * @return 百分比，{@code 0} 表示沿用旧口径
     */
    public int getAgingPercent() {
        return agingPercent;
    }

    /**
     * 判断是否与缺省值完全一致。
     * <p>
     * 供 {@link ReactSettings#isDefault()} 判断「整段是否什么都没配」，因此只比较是否等于缺省，
     * 不比较字段来源。
     *
     * @return 等于缺省值返回 {@code true}
     */
    public boolean isDefault() {
        return agingPercent == DEFAULT_AGING_PERCENT;
    }
}
