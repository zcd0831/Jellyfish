package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellResult} 的单元测试。
 * <p>
 * 元数据行是它在整个设计里唯一的对外职责：它会成为输出正文的首行，因此「退出码 / 终止原因 /
 * 工作目录 / 耗时」必须出现在同一个位置、并且如实表达是四条终止路径里的哪一条。
 *
 * @author zcd
 */
@DisplayName("ShellResult")
class ShellResultTest {

    @Test
    @DisplayName("正常结束后元数据行给出工作目录、退出码与耗时")
    void summary_should_reportExitCode_when_completed() {
        ShellResult result = ShellResult.of(ShellResult.Termination.COMPLETED, Integer.valueOf(0), 1234L,
                false, 0L);

        String summary = result.summary("/work");

        assertTrue(summary.startsWith("cwd: /work · "), summary);
        assertTrue(summary.contains("exit: 0"), summary);
        assertTrue(summary.contains("耗时: 1.2 秒"), summary);
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("非零退出码不是成功，但仍是「命令自己跑完了」")
    void isSuccess_should_beFalse_when_exitCodeNonZero() {
        ShellResult result = ShellResult.of(ShellResult.Termination.COMPLETED, Integer.valueOf(2), 10L,
                false, 0L);

        assertFalse(result.isSuccess());
        assertTrue(result.summary("/work").contains("exit: 2"), result.summary("/work"));
    }

    @Test
    @DisplayName("超时终止时说清是超时，不报退出码——退出码只反映我们发的信号")
    void summary_should_reportTimeout_withoutExitCode() {
        ShellResult result = ShellResult.of(ShellResult.Termination.TIMEOUT, null, 120_000L, false, 0L);

        String summary = result.summary("/work");

        assertTrue(summary.contains("已超时"), summary);
        assertFalse(summary.contains("exit:"), summary);
    }

    @Test
    @DisplayName("静默终止与超时是两句话——它们要人做的处置完全不同")
    void summary_should_reportIdleTimeout_differently() {
        ShellResult result = ShellResult.of(ShellResult.Termination.IDLE_TIMEOUT, null, 300_000L, false, 0L);

        String summary = result.summary("/work");

        assertTrue(summary.contains("无输出"), summary);
        assertTrue(summary.contains("判定为卡住"), summary);
    }

    @Test
    @DisplayName("取消终止时如实说明是用户取消")
    void summary_should_reportCancellation() {
        ShellResult result = ShellResult.of(ShellResult.Termination.CANCELLED, null, 3_000L, false, 0L);

        assertTrue(result.summary("/work").contains("已取消"), result.summary("/work"));
    }

    @Test
    @DisplayName("二进制输出在元数据行里说明丢了多少字节")
    void summary_should_reportBinaryOutput() {
        ShellResult result = ShellResult.of(ShellResult.Termination.COMPLETED, Integer.valueOf(0), 10L,
                true, 4096L);

        assertTrue(result.summary("/work").contains("4096 字节已省略"), result.summary("/work"));
    }

    @Test
    @DisplayName("没有工作目录时元数据行不出现空的 cwd 段")
    void summary_should_skipCwd_when_absent() {
        ShellResult result = ShellResult.of(ShellResult.Termination.COMPLETED, Integer.valueOf(0), 10L,
                false, 0L);

        assertFalse(result.summary(null).contains("cwd:"), result.summary(null));
        assertFalse(result.summary("").contains("cwd:"), result.summary(""));
    }

    @Test
    @DisplayName("耗时用点号小数点，不随区域设置变（否则会混进看似千位分隔的数字）")
    void summary_should_useDotDecimalSeparator() {
        ShellResult result = ShellResult.of(ShellResult.Termination.COMPLETED, Integer.valueOf(0), 1500L,
                false, 0L);

        assertTrue(result.summary(null).contains("1.5 秒"), result.summary(null));
    }
}
