package zcd.jellyfish.cli.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.registry.TypeRegistry;

import javax.inject.Singleton;

/**
 * 插件运行时相关依赖的 Dagger2 模块。
 * <p>
 * 三者的装配顺序值得写下来：{@link PluginRuntimeConfig}（装配输入）→ {@link PluginContextFactory}
 * （能力上下文与回收）→ {@link PF4JPluginManager}（加载、体检、热部署）。插件管理器因此只依赖
 * 「会造上下文、会回收」这一个协作者，不感知注册表与事件通道。
 * <p>
 * {@link PluginRuntimeConfig} 目前使用代码默认值（扫描 {@code plugins/} 目录、不限启用禁用）；
 * 等 {@code jellyfish.json} 的 {@code plugins} 段落地后，只需把这里换成读取双源配置的实现。
 *
 * @author zcd
 */
@Module
public final class PluginModule {

    private PluginModule() {
    }

    /**
     * 提供插件运行时装配输入。
     *
     * @return 装配输入
     */
    @Provides
    @Singleton
    static PluginRuntimeConfig providePluginRuntimeConfig() {
        return PluginRuntimeConfig.defaults();
    }

    /**
     * 提供插件上下文工厂：把 {@code pluginId} 绑定为 owner，并作为插件侧唯一回收入口。
     *
     * @param extensions 同步扩展点策略
     * @param events     事件通道
     * @param registry   共用注册表，用于按 owner 一次性回收
     * @return 插件上下文工厂
     */
    @Provides
    @Singleton
    static PluginContextFactory providePluginContextFactory(ExtensionRegistry extensions, EventChannel events,
                                                            TypeRegistry registry) {
        return new PluginContextFactory(extensions, events, registry);
    }

    /**
     * 提供插件运行时门面。
     *
     * @param contexts      插件上下文工厂
     * @param runtimeConfig 装配输入
     * @return 插件运行时门面
     */
    @Provides
    @Singleton
    static PF4JPluginManager providePluginManager(PluginContextFactory contexts,
                                                  PluginRuntimeConfig runtimeConfig) {
        return new PF4JPluginManager(contexts, runtimeConfig);
    }
}
