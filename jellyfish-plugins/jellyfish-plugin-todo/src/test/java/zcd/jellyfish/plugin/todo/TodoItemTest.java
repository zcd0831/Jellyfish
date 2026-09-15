package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoItem} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办项")
class TodoItemTest {

    @Test
    @DisplayName("构造应保留内容与完成标记")
    void constructor_should_keepContentAndDone() {
        TodoItem item = new TodoItem("写文档", true);

        assertEquals("写文档", item.content());
        assertTrue(item.done());
    }

    @Test
    @DisplayName("未完成项是默认形态")
    void constructor_should_defaultToPending() {
        assertFalse(new TodoItem("写文档", false).done());
    }

    @Test
    @DisplayName("内容为空白应被拒绝：它是渲染与注入的唯一依据")
    void constructor_should_rejectBlankContent() {
        assertThrows(JellyfishException.class, () -> new TodoItem("   ", false));
        assertThrows(JellyfishException.class, () -> new TodoItem(null, false));
    }
}
