package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventThreadFactory} 的单元测试：验证线程命名与守护线程标记。
 *
 * @author zcd
 */
class EventThreadFactoryTest {

    @Test
    void newThread_should_create_daemon_thread_with_prefixed_name() {
        // Given
        EventThreadFactory factory = new EventThreadFactory();

        // When
        Thread thread = factory.newThread(() -> {
            // 仅用于校验线程属性
        });

        // Then
        assertTrue(thread.getName().startsWith(EventThreadFactory.NAME_PREFIX));
        assertTrue(thread.isDaemon());
    }

    @Test
    void newThread_should_increment_sequence_for_each_thread() {
        // Given
        EventThreadFactory factory = new EventThreadFactory();

        // When
        Thread first = factory.newThread(() -> {
            // 仅用于校验线程序号
        });
        Thread second = factory.newThread(() -> {
            // 仅用于校验线程序号
        });

        // Then
        assertNotEquals(first.getName(), second.getName());
    }
}
