package zcd.jellyfish.infra.event;

import com.google.common.eventbus.SubscriberExceptionContext;
import com.google.common.eventbus.SubscriberExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.ExtensionRequest;

/**
 * 订阅者异常处理器：回调通道尝试回填应答槽，通知通道只记账。
 * <p>
 * 由于 dispatcher 已自行 try/catch 处理业务异常，这里不再是主路径，只兜底「dispatcher 自身出错」的情况。
 * 保留它是为了不让任何异常静默消失。
 *
 * @author zcd
 */
final class EventDispatchExceptionHandler implements SubscriberExceptionHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(EventDispatchExceptionHandler.class);

    /** 与门面共享的回调派发上下文。 */
    private final DispatchContext context;

    /** 回调应答槽，用于兜底回填回调失败。 */
    private final CallbackReplies callbackReplies;

    /**
     * 构造异常处理器。
     *
     * @param context        回调派发上下文
     * @param callbackReplies 回调应答槽
     */
    EventDispatchExceptionHandler(DispatchContext context, CallbackReplies callbackReplies) {
        this.context = context;
        this.callbackReplies = callbackReplies;
    }

    @Override
    public void handleException(Throwable exception, SubscriberExceptionContext eventContext) {
        ExtensionRequest<?> callback = context.current();
        if (callback != null) {
            callbackReplies.fail(callback, exception);
            return;
        }
        context.stats().subscriberErrors.increment();
        LOG.error("订阅者异常: subscriber={} method={} event={}",
                eventContext.getSubscriber(), eventContext.getSubscriberMethod(),
                eventContext.getEvent().getClass().getName(), exception);
    }
}
