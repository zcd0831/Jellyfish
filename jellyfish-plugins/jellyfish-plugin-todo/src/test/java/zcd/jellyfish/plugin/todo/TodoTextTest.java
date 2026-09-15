package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoText} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办文本渲染")
class TodoTextTest {

    @Test
    @DisplayName("注入 system prompt 的形态要与内核原先一致")
    void promptBlock_should_renderKernelCompatibleBlock() {
        List<TodoItem> items = Arrays.asList(new TodoItem("写文档", false), new TodoItem("跑测试", true));

        String block = TodoText.promptBlock(items);

        assertEquals("[待办]\n- [ ] 写文档\n- [x] 跑测试", block);
    }

    @Test
    @DisplayName("没有待办时返回 null，让调用点表达「无贡献」而不是注入空块")
    void promptBlock_should_returnNull_when_empty() {
        assertNull(TodoText.promptBlock(Collections.<TodoItem>emptyList()));
    }

    @Test
    @DisplayName("给人看的清单带位置编号，并明确说明为空")
    void list_should_renderNumberedLines() {
        assertEquals("当前没有待办。", TodoText.list(Collections.<TodoItem>emptyList()));
        assertEquals("待办：\n  [ ] 1. 写文档\n  [x] 2. 跑测试",
                TodoText.list(Arrays.asList(new TodoItem("写文档", false), new TodoItem("跑测试", true))));
    }

    @Test
    @DisplayName("工具确认文本在清单为空时说清「已清空」")
    void confirmation_should_describeBothOutcomes() {
        assertTrue(TodoText.confirmation(Collections.<TodoItem>emptyList()).contains("清空"));
        assertTrue(TodoText.confirmation(Collections.singletonList(new TodoItem("写文档", false)))
                .contains("[ ] 1. 写文档"));
    }

    @Test
    @DisplayName("状态栏片段只给进度：一行宽度放不下清单")
    void statusLine_should_renderProgressOnly() {
        assertEquals("待办 1/2", TodoText.statusLine(
                Arrays.asList(new TodoItem("写文档", true), new TodoItem("跑测试", false))));
        assertEquals("待办 0/1", TodoText.statusLine(
                Collections.singletonList(new TodoItem("写文档", false))));
    }

    @Test
    @DisplayName("没有待办时状态栏片段为 null，让调用点表达「不显示」")
    void statusLine_should_returnNull_when_empty() {
        assertNull(TodoText.statusLine(Collections.<TodoItem>emptyList()));
    }
}
