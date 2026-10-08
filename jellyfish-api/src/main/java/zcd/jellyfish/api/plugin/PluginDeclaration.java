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

    /** 插件配置段（两级合并后的最终值），未配置时为空映射。 */
    private final Map<String, Object> configuration;

    /** 配置段里只由全局级决定的那一份。 */
    private final Map<String, Object> globalConfiguration;

    /** 配置段的来源层级。 */
    private final PluginConfigScope configScope;

    /**
     * 构造声明。
     *
     * @param pluginId            插件标识，不可为空白
     * @param configuration       配置段（合并后的最终值），可为 {@code null}
     * @param globalConfiguration 只由全局级决定的那份配置，可为 {@code null}
     * @param configScope         配置段来源层级，可为 {@code null}（按 {@link PluginConfigScope#UNKNOWN} 处理）
     * @throws JellyfishException 插件标识为空白时抛出
     */
    private PluginDeclaration(String pluginId, Map<String, Object> configuration,
                              Map<String, Object> globalConfiguration, PluginConfigScope configScope) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            throw new JellyfishException("pluginId must not be blank");
        }
        this.pluginId = pluginId;
        this.configuration = copy(configuration);
        this.globalConfiguration = copy(globalConfiguration);
        this.configScope = configScope == null ? PluginConfigScope.UNKNOWN : configScope;
    }

    /**
     * 构造无配置段的声明。
     *
     * @param pluginId 插件标识，不可为空白
     * @return 插件声明
     * @throws JellyfishException 插件标识为空白时抛出
     */
    public static PluginDeclaration of(String pluginId) {
        return new PluginDeclaration(pluginId, null, null, PluginConfigScope.ABSENT);
    }

    /**
     * 构造只有一份配置的声明。
     * <p>
     * <b>「只有一份」时来源记 {@link PluginConfigScope#UNKNOWN} 而不是 GLOBAL</b>：调用方（测试桩、
     * 手写装配）并没有告诉这里这一份是哪来的，而 GLOBAL 是一个断言——插件会据此认定「这个值
     * 不受仓库内容影响」。宁可让它按「不区分来源」处理，也不要替调用方下一个错的结论。
     *
     * @param pluginId      插件标识，不可为空白
     * @param configuration 配置段，可为 {@code null}
     * @return 插件声明
     * @throws JellyfishException 插件标识为空白时抛出
     */
    public static PluginDeclaration of(String pluginId, Map<String, Object> configuration) {
        return new PluginDeclaration(pluginId, configuration, configuration, PluginConfigScope.UNKNOWN);
    }

    /**
     * 构造区分配置来源的完整声明：内核装配走这条路径。
     * <p>
     * <b>为什么要给两份</b>：两级合并时同名插件段是整对象替换，因此「合并值」可能是项目级那份；
     * 而安全边界类的键（提示内联上限、加载目录范围）只能认全局级那份，同时也不能把全局级设的值
     * 一起丢掉——见 {@code PluginContext.globalConfiguration()}。
     *
     * @param pluginId            插件标识，不可为空白
     * @param configuration       配置段（合并后的最终值），可为 {@code null}
     * @param globalConfiguration 只由全局级决定的那份配置，可为 {@code null}
     * @param configScope         配置段来源层级，可为 {@code null}（按 {@link PluginConfigScope#UNKNOWN} 处理）
     * @return 插件声明
     * @throws JellyfishException 插件标识为空白时抛出
     */
    public static PluginDeclaration of(String pluginId, Map<String, Object> configuration,
                                       Map<String, Object> globalConfiguration, PluginConfigScope configScope) {
        return new PluginDeclaration(pluginId, configuration, globalConfiguration, configScope);
    }

    /**
     * 复制配置段并包装为不可变映射。
     *
     * @param configuration 配置段，可为 {@code null}
     * @return 不可变映射，入参为 {@code null} 时返回空映射
     */
    private static Map<String, Object> copy(Map<String, Object> configuration) {
        return configuration == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
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
     * 获取插件配置段（两级合并后的最终值）。
     *
     * @return 不可变映射，未配置时为空映射而非 {@code null}
     */
    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    /**
     * 获取只由全局级决定的那份配置。
     *
     * @return 不可变映射，全局级未配置该插件段时为空映射而非 {@code null}
     */
    public Map<String, Object> getGlobalConfiguration() {
        return globalConfiguration;
    }

    /**
     * 获取配置段的来源层级。
     *
     * @return 来源层级，保证非 {@code null}
     */
    public PluginConfigScope getConfigScope() {
        return configScope;
    }

    @Override
    public String toString() {
        // 配置段可能含密钥，诊断输出只带标识
        return "PluginDeclaration{pluginId=" + pluginId + '}';
    }
}
