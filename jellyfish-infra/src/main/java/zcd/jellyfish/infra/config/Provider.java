package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个 LLM 服务商定义，对应配置文件 {@code providers} 中的一个条目。
 * <p>
 * 不可变：所有字段在构造时确定，合并时通过 {@link #withName(String)} 产生副本，不会改动原始对象。
 * {@code name} 由配置文件中该条目的 key 回填（见 {@code RuntimeConfig} 的合并逻辑）；合并时同一 key
 * 的项目级定义<b>整对象替换</b>全局级定义，不同 key 视为新增 provider。
 *
 * @author zcd
 */
public class Provider {

    /** provider 名，取自配置文件中 providers 的 key。 */
    private final String name;

    /** provider 类型，取值见 {@code ProviderTypes}，用于路由到具体 LlmClient 实现。 */
    private final String type;

    /** 访问密钥，通常由配置中的 {@code ${ENV_VAR}} 注入。 */
    private final String apiKey;

    /** 服务地址，留空时由具体客户端使用默认地址。 */
    private final String baseUrl;

    /** 该 provider 下可用模型列表，不可变；未配置时为空列表而非 {@code null}。 */
    private final List<Model> models;

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param name    provider 名，可为 {@code null}（由合并阶段按配置 key 回填）
     * @param type    provider 类型
     * @param apiKey  访问密钥
     * @param baseUrl 服务地址
     * @param models  模型列表，可为 {@code null}
     */
    @JsonCreator
    public Provider(@JsonProperty("name") String name,
                    @JsonProperty("type") String type,
                    @JsonProperty("apiKey") String apiKey,
                    @JsonProperty("baseUrl") String baseUrl,
                    @JsonProperty("models") List<Model> models) {
        this.name = name;
        this.type = type;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.models = models == null
                ? Collections.<Model>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(models));
    }

    /**
     * 获取 provider 名。
     *
     * @return provider 名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取 provider 类型。
     *
     * @return provider 类型
     */
    public String getType() {
        return type;
    }

    /**
     * 获取 apiKey。
     *
     * @return apiKey
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 获取服务地址。
     *
     * @return 服务地址
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 获取模型列表。
     *
     * @return 模型列表，可能为空但不会为 {@code null}
     */
    public List<Model> getModels() {
        return models;
    }

    /**
     * 用给定的 provider 名产生一个副本，用于把配置文件的 key 回填为 provider 名而不改动原对象。
     *
     * @param newName 新的 provider 名
     * @return 除 name 外与当前对象完全一致的新实例
     */
    public Provider withName(String newName) {
        return new Provider(newName, type, apiKey, baseUrl, models);
    }
}
