package zcd.jellyfish.cli.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ConfigLoader;

import javax.inject.Singleton;

/**
 * 应用级配置的 Dagger2 模块。
 * <p>
 * {@link AppConfig} 是纯数据类，读取 classpath 的 {@code config.json} 需要文件 IO 与解析器，
 * 因此把「如何得到 AppConfig」放在最外层的 composition root，由 {@link ConfigLoader} 创建，
 * infra 只暴露不带副作用的构造器。组件与 Module 只存在于 {@code jellyfish-cli}。
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
}
