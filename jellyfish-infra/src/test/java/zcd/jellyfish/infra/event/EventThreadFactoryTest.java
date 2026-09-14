package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventThreadFactory} 的单元测试：验证命名前缀与守护线程。
 *
 * @author zcd
 */
class EventThreadFactoryTest {

    /** 被测线程工厂。 */
    private final EventThreadFactory factory = new EventThreadFactory();

    @Test
    void newThread_should_use_prefix_and_sequence() {
        // When
        Thread first = factory.newThread(() -> {
        });
        Thread second = factory.newThread(() -> {
        });

        // Then
        assertNotNull(first.getName());
        assertTrue(first.getName().startsWith(EventThreadFactory.NAME_PREFIX));
        assertTrue(second.getName().startsWith(EventThreadFactory.NAME_PREFIX));
        assertTrue(!first.getName().equals(second.getName()));
    }

    @Test
    void newThread_should_create_daemon_thread() {
        // When
        Thread thread = factory.newThread(() -> {
        });

        // Then：守护线程保证线程池不会阻止 JVM 退出
        assertTrue(thread.isDaemon());
    }
}
