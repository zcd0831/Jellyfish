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
 * <b>为什么只有这三项</b>：{@code temperature} / {@code topP} / {@code stop} 是<b>各家都有对应物</b>
 * 的参数，内核的 {@code LlmRequest} 也早就为它们留好了字段（此前只是没有配置入口，永远是
 * {@code null}）。其余常见的「生成参数」不满足这个条件，因此<b>不放这里</b>：
 * <ul>
 *     <li>{@code seed}：OpenAI 与 Gemini 有，Claude 没有；</li>
 *     <li>{@code frequency_penalty} / {@code presence_penalty}：同上；</li>
 *     <li>{@code reasoning_effort}（OpenAI 的枚举值）、{@code thinking.budget_tokens}
 *     （Anthropic 的整数）、{@code thinkingConfig.thinkingBudget}（Gemini）：<b>连语义与单位都不一致</b>，
 *     内核认了就等于替厂商背书一个不成立的抽象。</li>
 * </ul>
 * 这些走 {@code extraBody}（原样透传，内核不解释）——两条路的边界就是「内核有没有一个厂商无关的字段」。
 * <p>
 * <b>{@code null} 表示不下发该字段，而不是「用某个缺省值」</b>：与 {@code cacheRetention} 同一口径，
 * 内核不替厂商猜缺省值（猜错就是一次 400），厂商自己的缺省才是缺省。
 * <p>
 * <b>非法值丢弃并告警，不阻断启动</b>（本仓库既有口径，见 {@code ProviderCacheSettings}）：
 * {@code temperature} 要求非负且有限，{@code topP} 要求落在 {@code (0, 1]}。<b>上限各家不同</b>
 * （OpenAI 系 0–2，Anthropic 0–1），因此只校验内核能确定的那部分，越界交给厂商拒绝——
 * 替厂商守一个它自己都不统一的区间，只会让用户在没配错的时候收到内核的报错。
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

    /** 采样温度，{@code null} 表示不下发。 */
    private final Double temperature;

    /** 核采样概率，{@code null} 表示不下发。 */
    private final Double topP;

    /** 停止序列，空列表表示不下发。 */
    private final List<String> stop;

    /**
     * 构造一份「什么都不表态」的采样设置。
     */
    public SamplingSettings() {
        this(null, null, null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param temperature 采样温度，负数、非有限值或缺省视为不下发
     * @param topP        核采样概率，超出 {@code (0, 1]} 或缺省视为不下发
     * @param stop        停止序列，空白项会被丢弃；有效条目<b>原样保留</b>（停止序列里的空格、
     *                    换行可能就是它的一部分，替用户去空白会改掉语义）
     */
    @JsonCreator
    public SamplingSettings(@JsonProperty("temperature") Double temperature,
                            @JsonProperty("topP") Double topP,
                            @JsonProperty("stop") List<String> stop) {
        this.temperature = cleanTemperature(temperature);
        this.topP = cleanTopP(topP);
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
     * 获取停止序列。
     *
     * @return 停止序列，可能为空但不会为 {@code null}
     */
    public List<String> getStop() {
        return stop;
    }

    /**
     * 判断是否三项都没表态。
     *
     * @return 三项都未设置时返回 {@code true}
     */
    public boolean isEmpty() {
        return temperature == null && topP == null && stop.isEmpty();
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
