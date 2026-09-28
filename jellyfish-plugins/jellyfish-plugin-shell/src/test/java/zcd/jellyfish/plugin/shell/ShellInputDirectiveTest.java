package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.InputDirectiveRequest;
import zcd.jellyfish.api.extension.InputDirectiveResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellInputDirective} 的单元测试：验证「行首感叹号 → shell 工具调用」的翻译。
 * <p>
 * 重点锁住两件事：<b>它只声明意图、不执行</b>（结果为工具调用而非任何副作用），
 * 以及<b>命令原文原样传递</b>（不在插件里做任何「安全解析」，否则 {@code !} 与模型调用会分成两套行为）。
 *
 * @author zcd
 */
@DisplayName("ShellInputDirective 输入指令")
class ShellInputDirectiveTest {

    /** 被测处理器。 */
    private final ShellInputDirective directive = new ShellInputDirective();

    @Test
    @DisplayName("!ls -la 应翻译成对 shell 工具的一次调用")
    void handle_should_map_to_shell_tool_call() throws Exception {
        // When
        InputDirectiveResult result = directive.handle(new InputDirectiveRequest("!", "!ls -la", "s-1"));

        // Then
        assertTrue(result.isToolCall());
        assertEquals(ShellTool.TOOL_NAME, result.getToolName());
        assertEquals("ls -la", result.getArguments().get("command"));
    }

    @Test
    @DisplayName("命令原文里的管道与重定向应原样传递")
    void handle_should_keep_command_verbatim() throws Exception {
        // When
        InputDirectiveResult result = directive.handle(
                new InputDirectiveRequest("!", "!ps aux | grep java > /tmp/x", "s-1"));

        // Then
        assertEquals("ps aux | grep java > /tmp/x", result.getArguments().get("command"));
    }

    @Test
    @DisplayName("只有感叹号时应交出空命令，交给工具侧报错")
    void handle_should_pass_blank_command_through() throws Exception {
        // When
        InputDirectiveResult result = directive.handle(new InputDirectiveRequest("!", "!", "s-1"));

        // Then：错误文案只有 shell 工具那一处，插件不重复校验
        assertTrue(result.isToolCall());
        assertEquals("", result.getArguments().get("command"));
    }

    @Test
    @DisplayName("不以感叹号开头时不认领")
    void handle_should_not_claim_when_marker_missing() throws Exception {
        // When
        InputDirectiveResult result = directive.handle(new InputDirectiveRequest("!", "ls -la", "s-1"));

        // Then
        assertFalse(result.isToolCall());
    }
}
