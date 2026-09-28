package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("元数据与首行结论同源：正常结束时带退出码与终止原因")
    void metadata_should_carryExitCodeAndTerminal_when_completed() {
        ShellResult result = ShellResult.of(ShellResult.Termination.COMPLETED, Integer.valueOf(1), 1234L,
                false, 0L);

        Map<String, Object> metadata = result.metadata();

        assertEquals(Integer.valueOf(1), metadata.get(ToolMetadata.KEY_EXIT_CODE));
        assertEquals("COMPLETED", metadata.get(ToolMetadata.KEY_TERMINAL));
        assertEquals(Long.valueOf(1234L), metadata.get("durationMs"));
        // 判据由内核约定统一给出，界面不必自己解释「1 算不算失败」
        assertTrue(ToolMetadata.failed(metadata));
    }

    @Test
    @DisplayName("被终止时不填退出码：那一档的退出码只反映我们发的信号")
    void metadata_should_omitExitCode_when_terminated() {
        ShellResult result = ShellResult.of(ShellResult.Termination.TIMEOUT, null, 120_000L, false, 0L);

        Map<String, Object> metadata = result.metadata();

        assertFalse(metadata.containsKey(ToolMetadata.KEY_EXIT_CODE));
        assertEquals("TIMEOUT", metadata.get(ToolMetadata.KEY_TERMINAL));
        assertTrue(ToolMetadata.failed(metadata), "超时必须被界面标出来");
    }

    @Test
    @DisplayName("二进制输出把「省略了多少字节」也变成字段")
    void metadata_should_carryBinaryBytes_when_binaryOutput() {
        ShellResult result = ShellResult.of(ShellResult.Termination.COMPLETED, Integer.valueOf(0), 5L,
                true, 512L);

        Map<String, Object> metadata = result.metadata();

        assertEquals(Boolean.TRUE, metadata.get("binary"));
        assertEquals(Long.valueOf(512L), metadata.get("binaryBytes"));
        assertFalse(ToolMetadata.failed(metadata), "二进制输出不等于命令失败");
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
