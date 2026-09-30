package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompactionDirective} 的单元测试：锁住「拦下与改保留条数互斥」这条契约。
 *
 * @author zcd
 */
@DisplayName("CompactionDirective 压缩指令")
class CompactionDirectiveTest {

    @Test
    void proceed_should_return_singleton_without_override() {
        // When
        CompactionDirective directive = CompactionDirective.proceed();

        // Then
        assertFalse(directive.isCancelled());
        assertFalse(directive.hasKeepRecent());
        assertNull(directive.getKeepRecent());
        assertSame(CompactionDirective.proceed(), directive);
    }

    @Test
    void cancel_should_carry_reason() {
        // When
        CompactionDirective directive = CompactionDirective.cancel("长任务正在跑");

        // Then
        assertTrue(directive.isCancelled());
        assertEquals("长任务正在跑", directive.getReason());
    }

    @Test
    void keepRecent_should_not_be_cancelled() {
        // 想拦下就不该再谈保留多少条：那一次压根不压。两者互斥，写成像「两件事都说了」会让调用点无所适从
        CompactionDirective directive = CompactionDirective.keepRecent(4);

        assertFalse(directive.isCancelled());
        assertTrue(directive.hasKeepRecent());
        assertEquals(Integer.valueOf(4), directive.getKeepRecent());
        assertNull(directive.getReason());
    }
}
