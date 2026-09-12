package zcd.jellyfish.api.event;

/**
 * 通知发布入口。
 * <p>
 * 这是窄接口，配置层与插件只依赖它，不感知具体事件总线实现。
 *
 * @author zcd
 */
@FunctionalInterface
public interface EventPublisher {

    /**
     * 异步广播一条通知。
     *
     * @param event 通知事件
     */
    void publish(JellyfishEvent event);
}
