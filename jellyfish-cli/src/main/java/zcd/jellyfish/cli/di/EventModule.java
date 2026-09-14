package zcd.jellyfish.cli.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.registry.TypeRegistry;

import javax.inject.Singleton;

/**
 * 事件通道相关依赖的 Dagger2 模块。
 * <p>
 * infra 只暴露带参数的构造器，组件与 Module 只存在于最外层 {@code jellyfish-cli}。
 * 这里额外提供一个窄接口视图 {@link EventPublisher}，让配置层只依赖接口。
 *
 * @author zcd
 */
@Module
public final class EventModule {

    private EventModule() {
    }

    /**
     * 提供事件通道参数。
     *
     * @return 事件通道参数
     */
    @Provides
    @Singleton
    static EventChannelOptions provideEventChannelOptions() {
        return EventChannelOptions.defaults();
    }

    /**
     * 提供事件通道单例。
     *
     * @param options  通道参数
     * @param registry 共用注册表
     * @return 事件通道
     */
    @Provides
    @Singleton
    static EventChannel provideEventChannel(EventChannelOptions options, TypeRegistry registry) {
        return new EventChannel(options, registry);
    }

    /**
     * 提供窄接口视图：配置层只依赖它。
     *
     * @param eventChannel 事件通道
     * @return 通知发布接口
     */
    @Provides
    static EventPublisher provideEventPublisher(EventChannel eventChannel) {
        return eventChannel;
    }
}
