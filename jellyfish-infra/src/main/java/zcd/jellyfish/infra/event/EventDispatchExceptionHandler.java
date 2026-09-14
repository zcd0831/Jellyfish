package zcd.jellyfish.infra.event;

import com.google.common.eventbus.SubscriberExceptionContext;
import com.google.common.eventbus.SubscriberExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅者异常处理器：只记账 + 记日志。
 * <p>
 * 本类是兜底而非主路径：{@code EventDispatcher} 已对每个订阅者做 try/catch，这里负责「dispatcher 自身出错」
 * 以及任何漏网异常，保证不让异常静默消失。
 * <p>
 * 同步扩展点的异常不再流经这里：它已改为调用点内联执行，异常原样回传给调用者，因此本类不再需要
 * 「把异常回填到应答槽」的能力。
 *
 * @author zcd
 */
final class EventDispatchExceptionHandler implements SubscriberExceptionHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(EventDispatchExceptionHandler.class);

    /** 指标。 */
    private final EventBusStats stats;

    /**
     * 构造异常处理器。
     *
     * @param stats 指标，不可为 {@code null}
     */
    EventDispatchExceptionHandler(EventBusStats stats) {
        this.stats = stats;
    }

    @Override
    public void handleException(Throwable exception, SubscriberExceptionContext eventContext) {
        stats.subscriberErrors.increment();
        LOG.error("订阅者异常: subscriber={} method={} event={}",
                eventContext.getSubscriber(), eventContext.getSubscriberMethod(),
                eventContext.getEvent().getClass().getName(), exception);
    }
}
