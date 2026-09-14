package zcd.jellyfish.infra.event;

import com.google.common.eventbus.SubscriberExceptionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * {@link EventDispatchExceptionHandler} 的单元测试：验证命令通道兜底回填与通知通道记账。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class EventDispatchExceptionHandlerTest {

    /** Guava 订阅者异常上下文，仅为构造日志参数而 mock。 */
    @Mock
    private SubscriberExceptionContext eventContext;

    @Test
    void handleException_should_fail_pending_command_when_command_in_flight() {
        // Given
        EventBusStats stats = new EventBusStats();
        DispatchContext context = new DispatchContext(2, stats);
        CallbackReplies replies = new CallbackReplies();
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);
        replies.open(callback);
        context.enter(callback);
        EventDispatchExceptionHandler handler = new EventDispatchExceptionHandler(context, replies);

        // When
        handler.handleException(new IllegalStateException("boom"), eventContext);

        // Then
        assertThrows(IllegalStateException.class, () -> replies.await(callback));
        assertEquals(0L, stats.getSubscriberErrors());
    }

    @Test
    void handleException_should_count_subscriber_error_when_no_command_in_flight() {
        // Given
        EventBusStats stats = new EventBusStats();
        DispatchContext context = new DispatchContext(2, stats);
        EventDispatchExceptionHandler handler = new EventDispatchExceptionHandler(context, new CallbackReplies());
        when(eventContext.getEvent()).thenReturn(new ConfigWarningEvent("path", "message"));

        // When
        handler.handleException(new IllegalStateException("boom"), eventContext);

        // Then
        assertEquals(1L, stats.getSubscriberErrors());
    }
}
