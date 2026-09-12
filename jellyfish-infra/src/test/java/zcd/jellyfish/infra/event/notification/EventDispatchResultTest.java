package zcd.jellyfish.infra.event.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EventDispatchResult} 的单元测试：验证命中数与错误数透出。
 *
 * @author zcd
 */
class EventDispatchResultTest {

    @Test
    void getters_should_return_constructed_counts() {
        // When
        EventDispatchResult result = new EventDispatchResult(3, 1);

        // Then
        assertEquals(3, result.getMatched());
        assertEquals(1, result.getErrors());
    }

    @Test
    void getters_should_return_zero_when_no_subscriber_matched() {
        // When
        EventDispatchResult result = new EventDispatchResult(0, 0);

        // Then
        assertEquals(0, result.getMatched());
        assertEquals(0, result.getErrors());
    }
}
