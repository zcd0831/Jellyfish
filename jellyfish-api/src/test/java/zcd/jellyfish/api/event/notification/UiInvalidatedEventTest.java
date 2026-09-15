package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiInvalidatedEvent} 的单元测试。
 * <p>
 * 它刻意没有业务字段，因此这里只守住两条不变量：公共元信息可用，以及「进程级事件」这个定位
 * （{@code sessionId} 为 {@code null}）不被无意改掉。
 *
 * @author zcd
 */
@DisplayName("UI 贡献失效事件")
class UiInvalidatedEventTest {

    @Test
    @DisplayName("是进程级事件：失效是整块缓存作废，与具体会话无关")
    void getSessionId_should_beNull() {
        assertNull(new UiInvalidatedEvent().getSessionId());
    }

    @Test
    @DisplayName("带上公共元信息，便于日志关联")
    void should_carryIdentityAndTimestamp() {
        UiInvalidatedEvent event = new UiInvalidatedEvent();

        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
    }
}
