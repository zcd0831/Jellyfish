package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型配置文件（{@code models.json}）反序列化后的原始结构。
 * <p>
 * 本类<b>只承载模型段</b>：插件段归 {@link JellyfishSettings}（其文件是 {@code jellyfish.json}）。
 * 类名与文件名一一对应，是仓库统一约定的一部分。
 * <p>
 * 该类只承载「单份文件」的内容，不做 global/project 合并；合并结果由 {@link RuntimeConfig} 产出，
 * 每次都构造新的 {@link ModelSettings} 实例，因此同一份文件被重复解析时互不影响。
 * <p>
 * 不可变：不存在 setter，{@code providers} 以不可变映射发布，读取方无法通过本对象回写配置。
 *
 * @author zcd
 */
public class ModelSettings {

    /** 默认 provider 名。 */
    private final String defaultProvider;

    /** 默认 model 名。 */
    private final String defaultModel;

    /** provider 名 → provider 定义，不可变；未配置时为空映射而非 {@code null}。 */
    private final Map<String, Provider> providers;

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param defaultProvider 默认 provider 名，可为 {@code null}
     * @param defaultModel    默认 model 名，可为 {@code null}
     * @param providers       provider 名到 provider 定义的映射，可为 {@code null}
     */
    @JsonCreator
    public ModelSettings(@JsonProperty("defaultProvider") String defaultProvider,
                         @JsonProperty("defaultModel") String defaultModel,
                         @JsonProperty("providers") Map<String, Provider> providers) {
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.providers = providers == null
                ? Collections.<String, Provider>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(providers));
    }

    /**
     * 获取默认 provider 名。
     *
     * @return 默认 provider 名，未配置时为 {@code null}
     */
    public String getDefaultProvider() {
        return defaultProvider;
    }

    /**
     * 获取默认 model 名。
     *
     * @return 默认 model 名，未配置时为 {@code null}
     */
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * 获取 provider 定义。
     *
     * @return provider 名到 provider 定义的映射，可能为空但不会为 {@code null}
     */
    public Map<String, Provider> getProviders() {
        return providers;
    }
}
