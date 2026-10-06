package zcd.jellyfish.infra.model;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.SamplingSettings;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.support.VendorBody;

import java.util.Collections;
import java.util.Map;

/**
 * 一次请求真正生效的调优参数：把 provider 级基线与 model 级覆盖合成一份结果。
 * <p>
 * <b>为什么要有这个中间类型</b>：「provider 给基线、model 覆盖」这条规则有三个消费者
 * （采样参数、直通请求体、直通请求头），如果各自在调用点现算，就会出现「三处合并规则长得不完全一样」
 * 这种没人能从行为上验证的差异。合成一次、发一份，规则只有一处。
 * <p>
 * <b>为什么连「输出上限的字段名」也放在这里</b>：它不是合并出来的值，只是模型的属性，但它与
 * {@code maxTokens} 是同一件事的两半——有值没名就是 400，有名没值就是白配。把它们收在同一个
 * 「落到请求上」的动作里（{@link #applyTo}），新增构造请求的路径就不可能只落一半：
 * 压缩回退路径就曾因为「自己写 maxTokens 而没写字段名」把摘要请求打成 400。
 * <p>
 * <b>为什么它不挂在 {@link ResolvedModel} 上</b>：解析结果回答的是「这次用哪个模型」，
 * 而本类回答的是「这次请求带什么参数」——后者随请求组装而变化（例如缓存保活与压缩 fork 只改内容、
 * 不改调优），混进解析结果会让那个值对象的职责变得说不清。
 * <p>
 * 合并规则：采样参数逐字段覆盖（见 {@link SamplingSettings#merge}）、直通请求体深合并
 * （见 {@link VendorBody#merge}）、直通请求头只有 provider 级、原样沿用，输出上限的字段名取模型级。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ModelTuning {

    /** 合并后的采样参数。 */
    private final SamplingSettings sampling;

    /** 合并后的直通请求体字段。 */
    private final Map<String, Object> vendorBody;

    /** 直通请求头（仅 provider 级）。 */
    private final Map<String, String> vendorHeaders;

    /** 承载输出上限的请求体字段名；{@code null} 表示用客户端缺省拼法。 */
    private final String maxTokensField;

    /**
     * 构造调优结果。
     *
     * @param sampling       合并后的采样参数，不可为 {@code null}
     * @param vendorBody     合并后的直通请求体字段，可为 {@code null}
     * @param vendorHeaders  直通请求头，可为 {@code null}
     * @param maxTokensField 承载输出上限的字段名，可为 {@code null}
     */
    private ModelTuning(SamplingSettings sampling, Map<String, Object> vendorBody,
                        Map<String, String> vendorHeaders, String maxTokensField) {
        this.sampling = sampling;
        this.vendorBody = vendorBody == null ? Collections.<String, Object>emptyMap() : vendorBody;
        this.vendorHeaders = vendorHeaders == null ? Collections.<String, String>emptyMap() : vendorHeaders;
        this.maxTokensField = maxTokensField;
    }

    /**
     * 由「provider 基线 + model 覆盖」合成调优结果。
     *
     * @param provider 命中的 provider，不可为 {@code null}
     * @param model    命中的 model，不可为 {@code null}
     * @return 调优结果，保证非 {@code null}
     * @throws JellyfishException 参数为 {@code null} 时抛出
     */
    public static ModelTuning of(Provider provider, Model model) {
        if (provider == null || model == null) {
            throw new JellyfishException("provider and model must not be null");
        }
        return new ModelTuning(
                SamplingSettings.merge(provider.getSampling(), model.getSampling()),
                VendorBody.merge(provider.getVendorBody(), model.getVendorBody()),
                provider.getVendorHeaders(),
                model.getMaxTokensField());
    }

    /**
     * 获取合并后的采样参数。
     *
     * @return 采样参数，保证非 {@code null}
     */
    public SamplingSettings getSampling() {
        return sampling;
    }

    /**
     * 获取合并后的直通请求体字段。
     *
     * @return 只读映射，可能为空但不会为 {@code null}
     */
    public Map<String, Object> getVendorBody() {
        return vendorBody;
    }

    /**
     * 获取直通请求头。
     *
     * @return 只读映射，可能为空但不会为 {@code null}
     */
    public Map<String, String> getVendorHeaders() {
        return vendorHeaders;
    }

    /**
     * 把这份调优落到请求构建器上：未表态的项不下发（{@code null} 与空列表都不设）。
     * <p>
     * <b>为什么把这一步收在这里而不是留在调用点</b>：请求不止一个来源——正常组装、压缩的 cache-safe fork、
     * 缓存保活，还有压缩在叉不动前缀时的「渲染成正文」回退路径。每个来源各写一遍「哪些字段要落、空值怎么办」，
     * 迟早会有一条悄悄少落几个字段，而它的症状是「同一个动作，走 A 路带参数、走 B 路不带」——
     * 既难复现也难归因。规则只留一处，新增请求来源时也不会漏。
     * <p>
     * <b>空值语义</b>：采样参数在 {@link SamplingSettings} 里已经归一（非法值就是 {@code null}），
     * 这里只管「不表态就不设」，不替厂商补缺省值。
     *
     * @param builder 请求构建器，可为 {@code null}（等价什么都不做）
     */
    public void applyTo(LlmRequest.Builder builder) {
        if (builder == null) {
            return;
        }
        builder.temperature(sampling.getTemperature())
                .topP(sampling.getTopP())
                .topK(sampling.getTopK())
                .seed(sampling.getSeed())
                .frequencyPenalty(sampling.getFrequencyPenalty())
                .presencePenalty(sampling.getPresencePenalty())
                .stop(sampling.getStop().isEmpty() ? null : sampling.getStop())
                .vendorBody(vendorBody)
                // 字段名与 maxTokens 是同一件事的两半，必须一起落：漏了它，目标模型可能只认另一种拼法，
                // 于是整个请求被 400 拒——而症状看起来与本次调用的目的毫无关系
                .maxTokensField(maxTokensField);
    }

    /**
     * 判断是否没有任何调优内容，用于跳过无意义的设置动作。
     *
     * @return 采样、直通请求体/头与输出上限字段名都没表态时返回 {@code true}
     */
    public boolean isEmpty() {
        return sampling.isEmpty() && vendorBody.isEmpty() && vendorHeaders.isEmpty()
                && maxTokensField == null;
    }
}
