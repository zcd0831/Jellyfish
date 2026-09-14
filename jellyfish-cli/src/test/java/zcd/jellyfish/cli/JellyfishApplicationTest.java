package zcd.jellyfish.cli;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.cli.console.RecordingConsoleIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishApplication} 的单元测试：只覆盖「不触碰内核装配」的三条路径——
 * 参数错误、帮助、版本。真正的启动链路由 {@link LauncherTest} 与端到端冒烟覆盖。
 *
 * @author zcd
 */
class JellyfishApplicationTest {

    @Test
    void run_should_return_usage_error_and_print_usage_when_args_invalid() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);

        int code = JellyfishApplication.run(new String[] {"-cli", "--nope"}, console);

        assertEquals(ExitCodes.USAGE_ERROR, code);
        assertTrue(console.err().contains("未知参数"));
        assertTrue(console.err().contains("用法：jellyfish"));
        assertEquals("", console.out());
    }

    @Test
    void run_should_return_ok_and_print_usage_to_stdout_when_help_given() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);

        int code = JellyfishApplication.run(new String[] {"-h"}, console);

        assertEquals(ExitCodes.OK, code);
        assertTrue(console.out().contains("用法：jellyfish"));
        assertEquals("", console.err());
    }

    @Test
    void run_should_return_ok_and_print_name_when_version_given() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);

        int code = JellyfishApplication.run(new String[] {"-V"}, console);

        assertEquals(ExitCodes.OK, code);
        assertTrue(console.out().startsWith("jellyfish "));
    }

    @Test
    void run_should_return_usage_error_when_mode_missing() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);

        int code = JellyfishApplication.run(new String[] {"-p", "你好"}, console);

        assertEquals(ExitCodes.USAGE_ERROR, code);
        assertTrue(console.err().contains("请指定启动模式"));
    }
}
