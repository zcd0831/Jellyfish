package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnDirective} 的单元测试：锁住「拦下与换输入互斥」这条契约。
 *
 * @author zcd
 */
@DisplayName("TurnDirective 回合指令")
class TurnDirectiveTest {

    @Test
    void proceed_should_return_singleton() {
        // When
        TurnDirective directive = TurnDirective.proceed();

        // Then
        assertFalse(directive.isCancelled());
        assertFalse(directive.hasInput());
        assertNull(directive.getInput());
        assertSame(TurnDirective.proceed(), directive);
    }

    @Test
    void cancel_should_carry_reason() {
        // When
        TurnDirective directive = TurnDirective.cancel("工作区有未提交的改动");

        // Then
        assertTrue(directive.isCancelled());
        assertEquals("工作区有未提交的改动", directive.getReason());
    }

    @Test
    void replaceInput_should_carry_text() {
        // When
        TurnDirective directive = TurnDirective.replaceInput("补了上下文");

        // Then
        assertFalse(directive.isCancelled());
        assertTrue(directive.hasInput());
        assertEquals("补了上下文", directive.getInput());
    }

    @Test
    void replaceInput_should_degrade_to_proceed_when_text_null() {
        // 用 null 同时表示「不改」与「改成空」会让调用点的判断无法解释，因此它当场退化成放行
        TurnDirective directive = TurnDirective.replaceInput(null);

        assertFalse(directive.hasInput());
        assertSame(TurnDirective.proceed(), directive);
    }
}
