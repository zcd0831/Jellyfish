package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoWriteTool} 的单元测试：重点是「模型传坏参数时必须当场报错，而不是静默落盘」。
 *
 * @author zcd
 */
@DisplayName("todo_write 工具")
class TodoWriteToolTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测工具。 */
    private TodoWriteTool tool;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        tool = new TodoWriteTool(store);
    }

    @Test
    @DisplayName("名片要能被模型读懂：名称、必填项与参数 Schema 缺一不可")
    void descriptor_should_describeToolAndRequiredArguments() {
        ToolDescriptor descriptor = TodoWriteTool.descriptor();

        assertEquals(TodoWriteTool.NAME, descriptor.getName());
        assertNotNull(descriptor.getDescription());
        assertEquals(Collections.singletonList("todos"), descriptor.getRequired());
        assertTrue(descriptor.getParameters().containsKey("todos"));
    }

    @Test
    @DisplayName("整表覆盖：新列表落盘并回显清单")
    void handle_should_replaceWholeList() {
        store.replace("s-1", Arrays.asList(new TodoItem("旧任务", false)));

        ToolCallResult result = tool.handle(request("s-1", item("写文档", "pending"), item("跑测试", "completed")));

        assertEquals(TodoWriteTool.NAME, result.getToolName());
        assertTrue(result.getOutput().toString().contains("[ ] 1. 写文档"));
        assertTrue(result.getOutput().toString().contains("[x] 2. 跑测试"));
        assertEquals(2, store.itemsOf("s-1").size());
        assertEquals("写文档", store.itemsOf("s-1").get(0).content());
    }

    @Test
    @DisplayName("空数组表示清空")
    void handle_should_clear_when_emptyArray() {
        store.replace("s-1", Arrays.asList(new TodoItem("旧任务", false)));

        ToolCallResult result = tool.handle(request("s-1"));

        assertTrue(result.getOutput().toString().contains("清空"));
        assertTrue(store.itemsOf("s-1").isEmpty());
    }

    @Test
    @DisplayName("缺少 status 按未完成处理：更容易改对的一侧")
    void handle_should_treatMissingStatusAsPending() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("content", "写文档");

        tool.handle(request("s-1", item));

        assertTrue(!store.itemsOf("s-1").get(0).done());
    }

    @Test
    @DisplayName("todos 不是数组应报错")
    void handle_should_rejectNonArray() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("todos", "写文档");

        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(TodoWriteTool.NAME,
                arguments, "s-1")));
    }

    @Test
    @DisplayName("todos 缺失应报错")
    void handle_should_rejectMissingTodos() {
        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(TodoWriteTool.NAME,
                new LinkedHashMap<String, Object>(), "s-1")));
    }

    @Test
    @DisplayName("列表项不是对象应报错")
    void handle_should_rejectNonObjectItem() {
        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(TodoWriteTool.NAME,
                arguments(Arrays.<Object>asList("写文档")), "s-1")));
    }

    @Test
    @DisplayName("content 为空白或缺失应报错")
    void handle_should_rejectBlankContent() {
        Map<String, Object> blank = new LinkedHashMap<String, Object>();
        blank.put("content", "   ");
        blank.put("status", "pending");

        assertThrows(JellyfishException.class, () -> tool.handle(request("s-1", blank)));
        assertThrows(JellyfishException.class, () -> tool.handle(request("s-1", item(null, "pending"))));
    }

    @Test
    @DisplayName("status 取值非法应报错并提示允许值")
    void handle_should_rejectUnknownStatus() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> tool.handle(request("s-1", item("写文档", "in_progress"))));

        assertTrue(error.getMessage().contains("pending"));
        assertTrue(error.getMessage().contains("completed"));
    }

    @Test
    @DisplayName("没有会话上下文时不写任何文件")
    void handle_should_rejectMissingSessionId() {
        Map<String, Object> arguments = arguments(Arrays.<Object>asList(item("写文档", "pending")));

        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(TodoWriteTool.NAME, arguments)));
    }

    /**
     * 构造一次工具调用请求。
     *
     * @param sessionId 会话标识
     * @param items     待办项参数
     * @return 工具调用请求
     */
    @SafeVarargs
    private static ToolCallRequest request(String sessionId, Map<String, Object>... items) {
        return new ToolCallRequest(TodoWriteTool.NAME, arguments(Arrays.<Object>asList(items)), sessionId);
    }

    /**
     * 构造工具参数映射。
     *
     * @param todos 待办项列表
     * @return 参数映射
     */
    private static Map<String, Object> arguments(List<Object> todos) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("todos", new ArrayList<Object>(todos));
        return arguments;
    }

    /**
     * 构造一条待办参数。
     *
     * @param content 内容，可为 {@code null}
     * @param status  状态，可为 {@code null}
     * @return 待办参数
     */
    private static Map<String, Object> item(String content, String status) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("content", content);
        item.put("status", status);
        return item;
    }
}
