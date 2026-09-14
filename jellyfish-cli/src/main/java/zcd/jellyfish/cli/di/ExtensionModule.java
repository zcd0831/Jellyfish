package zcd.jellyfish.cli.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import javax.inject.Singleton;

/**
 * 扩展层相关依赖的 Dagger2 模块。
 * <p>
 * 同步扩展点与异步通知共用同一份 {@link TypeRegistry}，因此这里把表本身也作为单例提供；
 * 两个派发策略各自是这份表之上的一个视图，不得各自再建一份表。
 *
 * @author zcd
 */
@Module
public final class ExtensionModule {

    private ExtensionModule() {
    }

    /**
     * 提供唯一一份类型注册表。
     *
     * @return 类型注册表
     */
    @Provides
    @Singleton
    static TypeRegistry provideTypeRegistry() {
        return new TypeRegistry();
    }

    /**
     * 提供同步扩展点策略。
     *
     * @param registry 共用注册表
     * @return 同步扩展点策略
     */
    @Provides
    @Singleton
    static ExtensionRegistry provideExtensionRegistry(TypeRegistry registry) {
        return new ExtensionRegistry(registry);
    }
}
