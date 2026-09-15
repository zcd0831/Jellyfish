package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoJson} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办 JSON 读写")
class TodoJsonTest {

    @Test
    @DisplayName("往返后每项内容与完成标记都要对得上")
    void read_should_restoreEveryField() {
        List<TodoItem> items = Arrays.asList(new TodoItem("写文档", false), new TodoItem("跑测试", true));

        List<TodoItem> restored = TodoJson.read(TodoJson.write(items), "test");

        assertEquals(2, restored.size());
        assertEquals("写文档", restored.get(0).content());
        assertTrue(!restored.get(0).done());
        assertEquals("跑测试", restored.get(1).content());
        assertTrue(restored.get(1).done());
    }

    @Test
    @DisplayName("空列表也要能往返")
    void read_should_roundTrip_when_empty() {
        assertTrue(TodoJson.read(TodoJson.write(java.util.Collections.<TodoItem>emptyList()), "test").isEmpty());
    }

    @Test
    @DisplayName("多出来的未知字段必须被忽略：旧版本读完新版本写的文件不该整段丢掉")
    void read_should_ignoreUnknownFields() {
        List<TodoItem> restored = TodoJson.read("[{\"content\":\"写文档\",\"done\":true,\"owner\":\"x\"}]", "test");

        assertEquals(1, restored.size());
        assertTrue(restored.get(0).done());
    }

    @Test
    @DisplayName("非法 JSON 应抛错而不是静默当作空列表")
    void read_should_fail_when_jsonInvalid() {
        assertThrows(JellyfishException.class, () -> TodoJson.read("{不是数组", "test"));
    }

    @Test
    @DisplayName("缺少 content 的项应被拒绝")
    void read_should_rejectMissingContent() {
        assertThrows(JellyfishException.class, () -> TodoJson.read("[{\"done\":false}]", "test"));
    }
}
