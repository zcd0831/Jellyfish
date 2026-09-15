package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoCommand} 的单元测试：命令是只读的，任何参数都应被明确拒绝。
 *
 * @author zcd
 */
@DisplayName("/todo 只读命令")
class TodoCommandTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测命令处理器。 */
    private TodoCommand command;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        command = new TodoCommand(store);
    }

    @Test
    @DisplayName("无参时列出当前会话待办")
    void handle_should_listSessionTodos() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", false), new TodoItem("跑测试", true)));

        CommandResult result = command.handle(new CommandRequest("todo", CommandArguments.EMPTY, "s-1"));

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("[ ] 1. 写文档"));
        assertTrue(result.getOutput().contains("[x] 2. 跑测试"));
    }

    @Test
    @DisplayName("没有待办时给出明确说明")
    void handle_should_reportEmpty() {
        CommandResult result = command.handle(new CommandRequest("todo", CommandArguments.EMPTY, "s-1"));

        assertEquals("当前没有待办。", result.getOutput());
    }

    @Test
    @DisplayName("带参数应报错：写入只走 todo_write，不给第二条写入语义")
    void handle_should_rejectArguments() {
        CommandResult result = command.handle(new CommandRequest("todo",
                new CommandArguments(Collections.singletonList("add"), "add"), "s-1"));

        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("/todo"));
    }

    @Test
    @DisplayName("没有会话上下文时明确报错而不是抛异常")
    void handle_should_reportError_when_noSession() {
        CommandResult result = command.handle(new CommandRequest("todo", CommandArguments.EMPTY, null));

        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("/new"));
    }
}
