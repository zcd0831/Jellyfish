package zcd.jellyfish.infra.event;

import com.google.common.eventbus.SubscriberExceptionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * {@link EventDispatchExceptionHandler} 的单元测试：验证兜底异常只记账不丢失。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class EventDispatchExceptionHandlerTest {

    /** Guava 订阅者异常上下文，仅为构造日志参数而 mock。 */
    @Mock
    private SubscriberExceptionContext eventContext;

    @Test
    void handleException_should_count_subscriber_error() {
        // Given
        EventBusStats stats = new EventBusStats();
        EventDispatchExceptionHandler handler = new EventDispatchExceptionHandler(stats);
        when(eventContext.getEvent()).thenReturn(new ConfigWarningEvent("path", "message"));

        // When
        handler.handleException(new IllegalStateException("boom"), eventContext);

        // Then
        assertEquals(1L, stats.getSubscriberErrors());
    }
}
