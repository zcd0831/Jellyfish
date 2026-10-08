package zcd.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ConfigLoader;
import zcd.jellyfish.infra.config.ProjectConfigTrust;

import javax.inject.Singleton;

/**
 * 应用级配置的 Dagger2 模块。
 * <p>
 * {@link AppConfig} 是纯数据类，读取 classpath 的 {@code config.json} 需要文件 IO 与解析器，
 * 因此把「如何得到 AppConfig」放在最外层的 composition root，由 {@link ConfigLoader} 创建，
 * infra 只暴露不带副作用的构造器。组件与 Module 只存在于 {@code jellyfish-di}。
 *
 * @author zcd
 */
@Module
public final class ConfigModule {

    private ConfigModule() {
    }

    /**
     * 读取 {@code classpath:config.json} 并作为单例提供。
     *
     * @param configLoader 配置读取门面
     * @return 应用级配置
     */
    @Provides
    @Singleton
    static AppConfig provideAppConfig(ConfigLoader configLoader) {
        return configLoader.loadAppConfig();
    }

    /**
     * 提供项目级配置的信任裁决。
     * <p>
     * <b>必须唯一</b>：授予信任发生在启动期（启动参数 / 交互确认），而读取它发生在
     * {@code RuntimeConfig.refresh()}；两边必须看到同一份状态，否则确认了却不生效。
     * 与 {@code JellyfishAssembler} 里那一行是同一件事的两种装法。
     *
     * @return 信任裁决
     */
    @Provides
    @Singleton
    static ProjectConfigTrust provideProjectConfigTrust() {
        return new ProjectConfigTrust();
    }
}
