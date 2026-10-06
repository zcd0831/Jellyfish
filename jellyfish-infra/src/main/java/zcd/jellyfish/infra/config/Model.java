package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import zcd.jellyfish.infra.support.ExtraBody;

import java.util.Map;

/**
 * 一个模型定义，隶属于某个 {@link Provider}。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 * <p>
 * <b>为什么采样与直通参数在模型级也有一份</b>：同一个 provider 下的模型并不都认同一套参数——
 * 推理模型收到 {@code reasoning_effort} 是正常用法，非推理模型收到它就是一次 400；温度也一样，
 * 摘要与主对话本来就该用不同的值。因此 provider 级给基线、模型级覆盖，合并规则见
 * {@link SamplingSettings#merge} 与 {@link ExtraBody#merge}。
 * <p>
 * <b>请求头没有模型级字段</b>：它是端点属性，同一个 provider 下所有模型共享同一套地址与网关规则。
 *
 * @author zcd
 */
public class Model {

    /** 模型标识，调用厂商接口时使用的 model 字段。 */
    private final String id;

    /** 模型展示名，可跨 provider 重名，是用户选择模型时的名称。 */
    private final String name;

    /** 上下文窗口长度（token）。 */
    private final int contextLength;

    /** 单次最大输出 token 数。 */
    private final int maxOutputTokens;

    /** 采样参数覆盖，不可为 {@code null}（未配置时三项都不表态，即沿用 provider 级）。 */
    private final SamplingSettings sampling;

    /** 直通请求体字段的模型级覆盖，不可变；未配置时为空映射。清洗规则见 {@link ExtraBody#sanitize}。 */
    private final Map<String, Object> extraBody;

    /**
     * 兼容旧调用点的便捷构造器：采样与直通段按缺省值处理。
     *
     * @param id              模型标识
     * @param name            模型展示名
     * @param contextLength   上下文窗口长度（token）
     * @param maxOutputTokens 单次最大输出 token 数
     */
    public Model(String id, String name, int contextLength, int maxOutputTokens) {
        this(id, name, contextLength, maxOutputTokens, null, null);
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
     * @param sampling        采样参数覆盖，{@code null} 按「三项都不表态」处理
     * @param extraBody       直通请求体字段的模型级覆盖，{@code null} 按空处理
     */
    @JsonCreator
    public Model(@JsonProperty("id") String id,
                 @JsonProperty("name") String name,
                 @JsonProperty("contextLength") int contextLength,
                 @JsonProperty("maxOutputTokens") int maxOutputTokens,
                 @JsonProperty("sampling") SamplingSettings sampling,
                 @JsonProperty("extraBody") Map<String, Object> extraBody) {
        this.id = id;
        this.name = name;
        this.contextLength = contextLength;
        this.maxOutputTokens = maxOutputTokens;
        this.sampling = sampling == null ? new SamplingSettings() : sampling;
        this.extraBody = ExtraBody.sanitize(extraBody, "model[" + (id == null ? "?" : id) + "]");
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
     * 与 {@link Provider#getExtraBody()} 同为「内核不解释」的映射，它会被深合并到 provider 级之上。
     *
     * @return 只读映射，可能为空但不会为 {@code null}
     */
    public Map<String, Object> getExtraBody() {
        return extraBody;
    }
}
