package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code models.json} 里 {@code sampling} 段：一次请求的采样参数，provider 级与 model 级都可写。
 * <p>
 * <b>收录标准只有一条：多家厂商有「同名同义」的对应物</b>，因此这里放的全是内核说得清语义、
 * 客户端各自翻得成自家协议字段的参数：
 * <ul>
 *     <li>{@code temperature} / {@code topP} / {@code topK}——采样策略，四家都有对应物
 *     （{@code topK} 仅 Anthropic 与 Gemini 认，OpenAI 系不下发即可，见下）；</li>
 *     <li>{@code stop}——停止序列；</li>
 *     <li>{@code seed}——随机种子，OpenAI 与 Gemini 认；</li>
 *     <li>{@code frequencyPenalty} / {@code presencePenalty}——重复与话题惩罚，OpenAI 与 Gemini 认。</li>
 * </ul>
 * <b>不在这里的参数，都因为「同义」这一条不成立</b>：{@code seed} 之外的 {@code n} / {@code candidateCount}
 * 与内核「一个候选对应一条 assistant 消息」的流式与工具调用逻辑直接冲突；{@code response_format}、
 * {@code service_tier}、{@code logit_bias}、{@code logprobs} 是单家能力；而<b>推理控制最典型</b>——
 * DeepSeek 是 {@code thinking.type} 加 {@code reasoning_effort}、OpenAI 只有 {@code reasoning_effort}、
 * Anthropic 换成了 {@code thinking.type: adaptive} 加 {@code output_config.effort}、Gemini 是
 * {@code thinkingConfig.thinkingLevel}，四家四种形状、四种取值，内核认了就等于替厂商背书一个不成立的抽象。
 * 这些走 {@code vendorBody}（原样透传，内核不解释）。
 * <p>
 * <b>{@code null} 表示不下发该字段，而不是「用某个缺省值」</b>：与 {@code cacheRetention} 同一口径，
 * 内核不替厂商猜缺省值（猜错就是一次 400），厂商自己的缺省才是缺省。
 * <p>
 * <b>客户端按「自家认不认」决定下发</b>：内核只声明意图。因此给 Anthropic 配 {@code seed}、
 * 给 OpenAI 系配 {@code topK} 都不会报错，只是那一项不会被写进请求体。
 * <p>
 * <b>非法值丢弃并告警，不阻断启动</b>（本仓库既有口径，见 {@code ProviderCacheSettings}）：
 * {@code temperature} 非负且有限、{@code topP} 落在 {@code (0, 1]}、{@code topK} 为正整数、
 * 两个惩罚项落在 {@code [-2, 2]}、{@code stop} 丢弃空白项。
 * <b>上限只校验内核能确定的那部分</b>（温度上限各家不同：OpenAI 系 0–2、Anthropic 0–1），越界交给厂商拒绝——
 * 替厂商守一个它自己都不统一的区间，只会让用户在没配错的时候收到内核的报错。
 * <p>
 * <b>一个已知的「配了不生效」</b>：部分厂商的思考模式会忽略采样参数（DeepSeek 官方写明思考模式下
 * {@code temperature} / {@code top_p} / 两个惩罚项都不生效，且不报错）。这属于厂商侧行为，
 * 与「哪个模型认哪些参数」一样是厂商知识，内核不替它判断，只在文档里写明。
 * <p>
 * 不可变：所有字段在构造时确定，{@code stop} 以不可变列表发布。
 *
 * @author zcd
 */
public class SamplingSettings {

    /** 配置期告警用。 */
    private static final Logger LOG = LoggerFactory.getLogger(SamplingSettings.class);

    /** {@code topP} 的合法上界：概率不能大于 1，这一条各厂商一致。 */
    private static final double TOP_P_MAX = 1.0d;

    /** 惩罚项的下界：OpenAI 与 Gemini 都是 {@code [-2, 2]}。 */
    private static final double PENALTY_MIN = -2.0d;

    /** 惩罚项的上界。 */
    private static final double PENALTY_MAX = 2.0d;

    /** 采样温度，{@code null} 表示不下发。 */
    private final Double temperature;

    /** 核采样概率，{@code null} 表示不下发。 */
    private final Double topP;

    /** Top-K 采样：只从概率最高的 K 个 token 里选，{@code null} 表示不下发。 */
    private final Integer topK;

    /** 随机种子，{@code null} 表示不下发。 */
    private final Long seed;

    /** 频率惩罚，{@code null} 表示不下发。 */
    private final Double frequencyPenalty;

    /** 存在惩罚，{@code null} 表示不下发。 */
    private final Double presencePenalty;

    /** 停止序列，空列表表示不下发。 */
    private final List<String> stop;

    /**
     * 构造一份「什么都不表态」的采样设置。
     */
    public SamplingSettings() {
        this(null, null, null);
    }

    /**
     * 便捷构造器：只表态三个基础采样参数，其余四项按「不下发」处理。
     *
     * @param temperature 采样温度
     * @param topP        核采样概率
     * @param stop        停止序列
     */
    public SamplingSettings(Double temperature, Double topP, List<String> stop) {
        this(temperature, topP, null, null, null, null, stop);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param temperature      采样温度，负数、非有限值或缺省视为不下发
     * @param topP             核采样概率，超出 {@code (0, 1]} 或缺省视为不下发
     * @param topK             Top-K 采样，非正整数或缺省视为不下发
     * @param seed             随机种子，缺省视为不下发
     * @param frequencyPenalty 频率惩罚，超出 {@code [-2, 2]} 或缺省视为不下发
     * @param presencePenalty  存在惩罚，超出 {@code [-2, 2]} 或缺省视为不下发
     * @param stop             停止序列，空白项会被丢弃；有效条目<b>原样保留</b>（停止序列里的空格、
     *                         换行可能就是它的一部分，替用户去空白会改掉语义）
     */
    @JsonCreator
    public SamplingSettings(@JsonProperty("temperature") Double temperature,
                            @JsonProperty("topP") Double topP,
                            @JsonProperty("topK") Integer topK,
                            @JsonProperty("seed") Long seed,
                            @JsonProperty("frequencyPenalty") Double frequencyPenalty,
                            @JsonProperty("presencePenalty") Double presencePenalty,
                            @JsonProperty("stop") List<String> stop) {
        this.temperature = cleanTemperature(temperature);
        this.topP = cleanTopP(topP);
        this.topK = cleanTopK(topK);
        this.seed = seed;
        this.frequencyPenalty = cleanPenalty(frequencyPenalty, "frequencyPenalty");
        this.presencePenalty = cleanPenalty(presencePenalty, "presencePenalty");
        this.stop = cleanStop(stop);
    }

    /**
     * 逐字段合并：{@code override} 里表了态的字段覆盖 {@code base}，没表的沿用 {@code base}。
     * <p>
     * <b>缺省的含义是「沿用」而不是「清空」</b>：model 级通常只想改一个温度，让它把 provider 级
     * 其余参数一并抹掉，等于逼用户在每个 model 上重复抄一遍。代价是明说的——<b>model 级无法单独关掉
     * provider 级已配的某项</b>；要关掉就把那一项从 provider 级拿下来。
     *
     * @param base     基线（provider 级），可为 {@code null}
     * @param override 覆盖（model 级），可为 {@code null}
     * @return 合并结果，保证非 {@code null}
     */
    public static SamplingSettings merge(SamplingSettings base, SamplingSettings override) {
        if (base == null) {
            return override == null ? new SamplingSettings() : override;
        }
        if (override == null) {
            return base;
        }
        return new SamplingSettings(
                override.temperature != null ? override.temperature : base.temperature,
                override.topP != null ? override.topP : base.topP,
                override.topK != null ? override.topK : base.topK,
                override.seed != null ? override.seed : base.seed,
                override.frequencyPenalty != null ? override.frequencyPenalty : base.frequencyPenalty,
                override.presencePenalty != null ? override.presencePenalty : base.presencePenalty,
                override.stop.isEmpty() ? base.stop : override.stop);
    }

    /**
     * 获取采样温度。
     *
     * @return 采样温度，未设置时为 {@code null}
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * 获取核采样概率。
     *
     * @return 核采样概率，未设置时为 {@code null}
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * 获取 Top-K 采样值。
     * <p>
     * 只有 Anthropic（{@code top_k}）与 Gemini（{@code topK}）认这一项；OpenAI 系没有对应字段，
     * 客户端因此不会下发它。
     *
     * @return Top-K 值，未设置时为 {@code null}
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * 获取随机种子。
     * <p>
     * OpenAI 与 Gemini 认这一项；Anthropic 没有对应字段。
     * <p>
     * <b>它不保证可复现</b>：厂商的文档都只说「尽力而为」，因此不要把它当作确定性输出的开关。
     *
     * @return 随机种子，未设置时为 {@code null}
     */
    public Long getSeed() {
        return seed;
    }

    /**
     * 获取频率惩罚。
     *
     * @return 频率惩罚，未设置时为 {@code null}
     */
    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    /**
     * 获取存在惩罚。
     *
     * @return 存在惩罚，未设置时为 {@code null}
     */
    public Double getPresencePenalty() {
        return presencePenalty;
    }

    /**
     * 获取停止序列。
     *
     * @return 停止序列，可能为空但不会为 {@code null}
     */
    public List<String> getStop() {
        return stop;
    }

    /**
     * 判断是否七项都没表态。
     *
     * @return 全部未设置时返回 {@code true}
     */
    public boolean isEmpty() {
        return temperature == null && topP == null && topK == null && seed == null
                && frequencyPenalty == null && presencePenalty == null && stop.isEmpty();
    }

    /**
     * 校验采样温度：非负且有限才下发。
     *
     * @param value 原始值，可为 {@code null}
     * @return 合法值，非法时为 {@code null}
     */
    private static Double cleanTemperature(Double value) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value.isInfinite() || value < 0d) {
            LOG.warn("sampling.temperature 非法（要求非负且有限），已忽略该项: value={}", value);
            return null;
        }
        return value;
    }

    /**
     * 校验核采样概率：落在 {@code (0, 1]} 才下发。
     *
     * @param value 原始值，可为 {@code null}
     * @return 合法值，非法时为 {@code null}
     */
    private static Double cleanTopP(Double value) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value <= 0d || value > TOP_P_MAX) {
            LOG.warn("sampling.topP 非法（要求位于 (0, 1]），已忽略该项: value={}", value);
            return null;
        }
        return value;
    }

    /**
     * 校验 Top-K：正数才下发。
     * <p>
     * {@code 0} 与负数被丢弃而不是原样下发：Anthropic 的 {@code top_k} 只有大于 0 才启用，
     * Gemini 接受 0 但语义是「不限制」，两家的「0」含义并不一致，因此内核不猜。
     *
     * @param value 原始值，可为 {@code null}
     * @return 合法值，非法时为 {@code null}
     */
    private static Integer cleanTopK(Integer value) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            LOG.warn("sampling.topK 非法（要求为正整数），已忽略该项: value={}", value);
            return null;
        }
        return value;
    }

    /**
     * 校验惩罚项：落在 {@code [-2, 2]} 才下发。
     *
     * @param value 原始值，可为 {@code null}
     * @param name  字段名，仅用于告警文本
     * @return 合法值，非法时为 {@code null}
     */
    private static Double cleanPenalty(Double value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value < PENALTY_MIN || value > PENALTY_MAX) {
            LOG.warn("sampling.{} 非法（要求位于 [-2, 2]），已忽略该项: value={}", name, value);
            return null;
        }
        return value;
    }

    /**
     * 清洗停止序列：丢弃空白项，其余原样保留。
     *
     * @param values 原始值，可为 {@code null}
     * @return 不可变列表，无有效项时为空列表
     */
    private static List<String> cleanStop(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>(values.size());
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value);
            }
        }
        return result.isEmpty() ? Collections.<String>emptyList() : Collections.unmodifiableList(result);
    }
}
