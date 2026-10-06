package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.infra.support.VendorBody;

import java.util.Map;

/**
 * 一个模型定义，隶属于某个 {@link Provider}。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 * <p>
 * <b>为什么采样与直通参数在模型级也有一份</b>：同一个 provider 下的模型并不都认同一套参数——
 * 推理模型收到 {@code reasoning_effort} 是正常用法，非推理模型收到它就是一次 400；温度也一样，
 * 摘要与主对话本来就该用不同的值。因此 provider 级给基线、模型级覆盖，合并规则见
 * {@link SamplingSettings#merge} 与 {@link VendorBody#merge}。
 * <p>
 * <b>请求头没有模型级字段</b>：它是端点属性，同一个 provider 下所有模型共享同一套地址与网关规则。
 *
 * @author zcd
 */
public class Model {

    /** 输出上限的缺省字段名：绝大多数 OpenAI 兼容端点用的都是它。 */
    public static final String DEFAULT_MAX_TOKENS_FIELD = "max_tokens";

    /** 另一种合法的输出上限字段名：OpenAI 的推理模型（o1 / o3 / o4-mini）与 gpt-5 之后只认它。 */
    public static final String COMPLETION_MAX_TOKENS_FIELD = "max_completion_tokens";

    /** 配置期告警用。 */
    private static final Logger LOG = LoggerFactory.getLogger(Model.class);

    /** 模型标识，调用厂商接口时使用的 model 字段。 */
    private final String id;

    /** 模型展示名，可跨 provider 重名，是用户选择模型时的名称。 */
    private final String name;

    /** 上下文窗口长度（token）。 */
    private final int contextLength;

    /** 单次最大输出 token 数。 */
    private final int maxOutputTokens;

    /**
     * 承载「输出上限」的请求体字段名；{@code null} 表示用 {@link #DEFAULT_MAX_TOKENS_FIELD}。
     * <p>
     * <b>为什么需要它</b>：{@link #maxOutputTokens} 由内核下发，但<b>字段名各家不同、同一家不同代际也不同</b>——
     * OpenAI 的 o1 / o3 / o4-mini 与 gpt-5 之后<b>拒收</b> {@code max_tokens}（报
     * {@code Unsupported parameter: 'max_tokens' is not supported with this model}），只认
     * {@code max_completion_tokens}；而 DeepSeek、OpenRouter 这类 OpenAI 兼容端点只认 {@code max_tokens}。
     * 猜错就是一次 400，而「哪个模型认哪个字段」是厂商知识，内核不按模型名猜，因此交给配置显式声明。
     */
    private final String maxTokensField;

    /** 采样参数覆盖，不可为 {@code null}（未配置时三项都不表态，即沿用 provider 级）。 */
    private final SamplingSettings sampling;

    /** 直通请求体字段的模型级覆盖，不可变；未配置时为空映射。清洗规则见 {@link VendorBody#sanitize}。 */
    private final Map<String, Object> vendorBody;

    /**
     * 兼容旧调用点的便捷构造器：采样与直通段按缺省值处理。
     *
     * @param id              模型标识
     * @param name            模型展示名
     * @param contextLength   上下文窗口长度（token）
     * @param maxOutputTokens 单次最大输出 token 数
     */
    public Model(String id, String name, int contextLength, int maxOutputTokens) {
        this(id, name, contextLength, maxOutputTokens, null, null, null);
    }

    /**
     * 反序列化使用的构造器。
     * <p>
     * 直通段与 {@link Provider} 同样在<b>构造期</b>清洗：保留键、超深子树与非法值在这里就被丢弃并告警。
     *
     * @param id              模型标识
     * @param name            模型展示名
     * @param contextLength   上下文窗口长度（token）
     * @param maxOutputTokens 单次最大输出 token 数
     * @param maxTokensField  输出上限的字段名，{@code null} 或非法值按 {@link #DEFAULT_MAX_TOKENS_FIELD} 处理
     * @param sampling        采样参数覆盖，{@code null} 按「三项都不表态」处理
     * @param vendorBody      直通请求体字段的模型级覆盖，{@code null} 按空处理
     */
    @JsonCreator
    public Model(@JsonProperty("id") String id,
                 @JsonProperty("name") String name,
                 @JsonProperty("contextLength") int contextLength,
                 @JsonProperty("maxOutputTokens") int maxOutputTokens,
                 @JsonProperty("maxTokensField") String maxTokensField,
                 @JsonProperty("sampling") SamplingSettings sampling,
                 @JsonProperty("vendorBody") Map<String, Object> vendorBody) {
        this.id = id;
        this.name = name;
        this.contextLength = contextLength;
        this.maxOutputTokens = maxOutputTokens;
        this.maxTokensField = cleanMaxTokensField(maxTokensField, id);
        this.sampling = sampling == null ? new SamplingSettings() : sampling;
        this.vendorBody = VendorBody.sanitize(vendorBody, "model[" + (id == null ? "?" : id) + "]");
    }

    /**
     * 校验输出上限的字段名：只认内核知道怎么下发的两种。
     * <p>
     * <b>为什么不原样透传任意字符串</b>：这个键由<b>内核</b>写进请求体，写错既不会命中厂商字段、也没有任何提示
     * （输出上限被静默忽略），因此在内核能判定的范围内直接驳回。要支持第三种写法时在这里加一个取值即可。
     *
     * @param value 原始值，可为 {@code null}
     * @param id    模型标识，仅用于告警文本（多 provider 时靠它定位是哪一条配错了）
     * @return 合法值；{@code null} 或非法时为 {@code null}（表示用缺省字段名）
     */
    private static String cleanMaxTokensField(String value, String id) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        if (DEFAULT_MAX_TOKENS_FIELD.equals(trimmed) || COMPLETION_MAX_TOKENS_FIELD.equals(trimmed)) {
            return trimmed;
        }
        LOG.warn("model.maxTokensField 取值不受支持，已回退到 {}: model={} value={}",
                DEFAULT_MAX_TOKENS_FIELD, id == null ? "?" : id, trimmed);
        return null;
    }

    /**
     * 获取模型标识。
     *
     * @return 模型标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取模型展示名。
     *
     * @return 模型展示名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取上下文窗口长度。
     *
     * @return 上下文窗口长度（token）
     */
    public int getContextLength() {
        return contextLength;
    }

    /**
     * 获取单次最大输出 token 数。
     *
     * @return 单次最大输出 token 数
     */
    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * 获取承载输出上限的请求体字段名。
     * <p>
     * <b>只有 OpenAI 兼容客户端认它</b>：Claude 的 Messages API 固定叫 {@code max_tokens}（且必填），
     * Gemini 的生成参数固定住在 {@code generationConfig.maxOutputTokens}，两者都不看这个字段。
     *
     * @return 字段名；未配置时为 {@code null}，调用方按 {@link #DEFAULT_MAX_TOKENS_FIELD} 处理
     */
    public String getMaxTokensField() {
        return maxTokensField;
    }

    /**
     * 获取采样参数覆盖。
     *
     * @return 采样设置，保证非 {@code null}（未配置时三项都不表态）
     */
    public SamplingSettings getSampling() {
        return sampling;
    }

    /**
     * 获取直通请求体字段的模型级覆盖。
     * <p>
     * 与 {@link Provider#getVendorBody()} 同为「内核不解释」的映射，它会被深合并到 provider 级之上。
     *
     * @return 只读映射，可能为空但不会为 {@code null}
     */
    public Map<String, Object> getVendorBody() {
        return vendorBody;
    }
}
