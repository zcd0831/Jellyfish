package zcd.jellyfish.api.plugin;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件声明：插件的身份与配置段，由内核侧构造并交给插件上下文。
 * <p>
 * 插件自身不参与构造——否则「自己声明自己」就失去了边界的意义。本类不可变，可安全跨线程传递。
 * <p>
 * 这里<b>没有</b>「能注册哪些回调」的清单：注册边界由回调类型本身承载（插件拿不到的类型就注册不了），
 * 不存在需要事先声明、事后校验的中间层。
 *
 * @author zcd
 */
public final class PluginDeclaration {

    /** 插件标识，同时作为注册表的 owner。 */
    private final String pluginId;

    /** 插件配置段，未配置时为空映射。 */
    private final Map<String, Object> configuration;

    /**
     * 构造声明。
     *
     * @param pluginId      插件标识，不可为空白
     * @param configuration 配置段，可为 {@code null}
     * @throws JellyfishException 插件标识为空白时抛出
     */
    private PluginDeclaration(String pluginId, Map<String, Object> configuration) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            throw new JellyfishException("pluginId must not be blank");
        }
        this.pluginId = pluginId;
        this.configuration = configuration == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }

    /**
     * 构造无配置段的声明。
     *
     * @param pluginId 插件标识，不可为空白
     * @return 插件声明
     * @throws JellyfishException 插件标识为空白时抛出
     */
    public static PluginDeclaration of(String pluginId) {
        return new PluginDeclaration(pluginId, null);
    }

    /**
     * 构造完整声明。
     *
     * @param pluginId      插件标识，不可为空白
     * @param configuration 配置段，可为 {@code null}
     * @return 插件声明
     * @throws JellyfishException 插件标识为空白时抛出
     */
    public static PluginDeclaration of(String pluginId, Map<String, Object> configuration) {
        return new PluginDeclaration(pluginId, configuration);
    }

    /**
     * 获取插件标识。
     *
     * @return 插件标识
     */
    public String getPluginId() {
        return pluginId;
    }

    /**
     * 获取插件配置段。
     *
     * @return 不可变映射，未配置时为空映射而非 {@code null}
     */
    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    @Override
    public String toString() {
        // 配置段可能含密钥，诊断输出只带标识
        return "PluginDeclaration{pluginId=" + pluginId + '}';
    }
}
