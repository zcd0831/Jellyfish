package zcd.jellyfish.cli.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.infra.event.EventBusOptions;
import zcd.jellyfish.infra.event.JellyfishEventBus;

import javax.inject.Singleton;

/**
 * 事件总线相关依赖的 Dagger2 模块。
 * <p>
 * infra 只暴露带参数的构造器，组件与 Module 只存在于最外层 {@code jellyfish-cli}。
 * 这里额外提供一个窄接口视图 {@link EventPublisher}，让配置层与插件只依赖接口。
 *
 * @author zcd
 */
@Module
public final class EventModule {

    private EventModule() {
    }

    /**
     * 提供事件总线参数。
     *
     * @return 事件总线参数
     */
    @Provides
    @Singleton
    static EventBusOptions provideEventBusOptions() {
        return EventBusOptions.defaults();
    }

    /**
     * 提供事件总线单例。
     *
     * @param options 总线参数
     * @return 事件总线
     */
    @Provides
    @Singleton
    static JellyfishEventBus provideEventBus(EventBusOptions options) {
        return new JellyfishEventBus(options);
    }

    /**
     * 提供窄接口视图：配置层与插件只依赖它。
     *
     * @param eventBus 事件总线
     * @return 通知发布接口
     */
    @Provides
    static EventPublisher provideEventPublisher(JellyfishEventBus eventBus) {
        return eventBus;
    }
}
