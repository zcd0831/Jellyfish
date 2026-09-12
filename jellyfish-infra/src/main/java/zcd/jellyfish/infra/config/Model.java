package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一个模型定义，隶属于某个 {@link Provider}。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
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

    /**
     * 反序列化使用的构造器。
     *
     * @param id              模型标识
     * @param name            模型展示名
     * @param contextLength   上下文窗口长度（token）
     * @param maxOutputTokens 单次最大输出 token 数
     */
    @JsonCreator
    public Model(@JsonProperty("id") String id,
                 @JsonProperty("name") String name,
                 @JsonProperty("contextLength") int contextLength,
                 @JsonProperty("maxOutputTokens") int maxOutputTokens) {
        this.id = id;
        this.name = name;
        this.contextLength = contextLength;
        this.maxOutputTokens = maxOutputTokens;
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
}
