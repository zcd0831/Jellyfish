package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellArguments} 的单元测试：参数由模型生成，因此每一条校验都必须给出可读的错误文案。
 *
 * @author zcd
 */
@DisplayName("ShellArguments")
class ShellArgumentsTest {

    @Test
    @DisplayName("命令原文去掉首尾空白后返回")
    void requireCommand_should_trim() {
        assertEquals("ls -la", new ShellArguments(args("command", "  ls -la  ")).requireCommand());
    }

    @Test
    @DisplayName("缺少 command 时报错并指明参数名")
    void requireCommand_should_fail_when_missing() {
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> new ShellArguments(args()).requireCommand());

        assertTrue(failure.getMessage().contains("command"), failure.getMessage());
    }

    @Test
    @DisplayName("command 为空白或类型不对时报错")
    void requireCommand_should_fail_when_blankOrNotString() {
        assertThrows(JellyfishException.class, () -> new ShellArguments(args("command", "   ")).requireCommand());
        assertThrows(JellyfishException.class, () -> new ShellArguments(args("command", 42)).requireCommand());
    }

    @Test
    @DisplayName("cwd 未提供时返回 null，空白视为未提供")
    void optionalCwd_should_returnNull_when_absent() {
        assertNull(new ShellArguments(args()).optionalCwd());
        assertNull(new ShellArguments(args("cwd", "  ")).optionalCwd());
        assertEquals("/tmp", new ShellArguments(args("cwd", " /tmp ")).optionalCwd());
    }

    @Test
    @DisplayName("cwd 类型不对时报错")
    void optionalCwd_should_fail_when_notString() {
        assertThrows(JellyfishException.class, () -> new ShellArguments(args("cwd", 1)).optionalCwd());
    }

    @Test
    @DisplayName("timeout_seconds 支持数字与数字字符串")
    void optionalTimeout_should_acceptNumberAndNumericString() {
        assertEquals(30, new ShellArguments(args("timeout_seconds", 30)).optionalTimeoutSeconds());
        assertEquals(45, new ShellArguments(args("timeout_seconds", "45")).optionalTimeoutSeconds());
        assertEquals(60, new ShellArguments(args("timeout_seconds", 60.0d)).optionalTimeoutSeconds());
    }

    @Test
    @DisplayName("timeout_seconds 未提供时返回 -1（表示跟随配置）")
    void optionalTimeout_should_returnMinusOne_when_absent() {
        assertEquals(-1, new ShellArguments(args()).optionalTimeoutSeconds());
    }

    @Test
    @DisplayName("timeout_seconds 小于 1 时报错而不是静默当成「不超时」")
    void optionalTimeout_should_fail_when_notPositive() {
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> new ShellArguments(args("timeout_seconds", 0)).optionalTimeoutSeconds());

        assertTrue(failure.getMessage().contains("大于 0"), failure.getMessage());
    }

    @Test
    @DisplayName("timeout_seconds 不是整数时报错")
    void optionalTimeout_should_fail_when_notInteger() {
        assertThrows(JellyfishException.class,
                () -> new ShellArguments(args("timeout_seconds", "abc")).optionalTimeoutSeconds());
        assertThrows(JellyfishException.class,
                () -> new ShellArguments(args("timeout_seconds", 1.5d)).optionalTimeoutSeconds());
    }

    @Test
    @DisplayName("null 参数映射等价于空参数")
    void constructor_should_tolerateNullArguments() {
        assertThrows(JellyfishException.class, () -> new ShellArguments(null).requireCommand());
    }

    /**
     * 构造参数映射。
     *
     * @param namesAndValues 参数名与值交替
     * @return 参数映射
     */
    private static Map<String, Object> args(Object... namesAndValues) {
        Map<String, Object> arguments = new HashMap<String, Object>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            arguments.put((String) namesAndValues[index], namesAndValues[index + 1]);
        }
        return arguments;
    }
}
