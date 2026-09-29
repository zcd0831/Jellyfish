package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolFilter} 的单元测试：验证全放行、判据委托与空判据的归一。
 *
 * @author zcd
 */
class ToolFilterTest {

    @Test
    void none_should_accept_every_tool() {
        // When
        ToolFilter filter = ToolFilter.none();

        // Then
        assertTrue(filter.isNone());
        assertTrue(filter.accepts("read_file"));
        assertTrue(filter.accepts("write_file"));
        // 连 null 也放行：全放行的语义是「不做任何判断」，不是「判断并放行」
        assertTrue(filter.accepts(null));
    }

    @Test
    void of_should_delegate_to_predicate() {
        // When
        ToolFilter filter = ToolFilter.of("read_file"::equals);

        // Then
        assertFalse(filter.isNone());
        assertTrue(filter.accepts("read_file"));
        assertFalse(filter.accepts("write_file"));
    }

    @Test
    void of_should_return_none_when_predicate_null() {
        // When
        ToolFilter filter = ToolFilter.of(null);

        // Then：没有判据与「全放行」是同一件事，不该是两个对象
        assertSame(ToolFilter.none(), filter);
    }

    @Test
    void toString_should_distinguish_none_from_filtered() {
        // When / Then：诊断输出里要能一眼看出滤没滤
        assertEquals("ToolFilter{none}", ToolFilter.none().toString());
        assertEquals("ToolFilter{filtered}", ToolFilter.of(tool -> true).toString());
    }
}
