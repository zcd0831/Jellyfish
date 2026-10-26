package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回合小结：只汇总看得见的数字，没发生事就不刷屏。 */
class TurnStatsTest {

    @Test
    void 没有任何模型调用就不出小结() {
        TurnStats stats = new TurnStats();
        stats.reset();
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.TOOL_STARTED, "read_file（path=/a）"));

        assertNull(stats.summary());
    }

    @Test
    void 汇总调用次数用量步数与工具数() {
        TurnStats stats = new TurnStats();
        stats.reset();
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.USAGE, "+150 / 累计 300 / 上限 1000000"));
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.USAGE, "+50 / 累计 350 / 上限 1000000"));
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.TOOL_STARTED, "read_file（path=/a）"));
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.STEP_STARTED, "第 1/3 步：第一步"));

        String summary = stats.summary();

        assertTrue(summary.contains("2 次模型调用"), summary);
        assertTrue(summary.contains("+200 tokens"), summary);
        assertTrue(summary.contains("累计 350"), summary);
        assertTrue(summary.contains("上限 1000000"), summary);
        assertTrue(summary.contains("1/3 步"), summary);
        assertTrue(summary.contains("1 个工具"), summary);
    }

    @Test
    void 用量文案不合法也不崩() {
        TurnStats stats = new TurnStats();
        stats.reset();
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.USAGE, "厂商没给用量"));

        String summary = stats.summary();

        assertTrue(summary.contains("1 次模型调用"), summary);
        assertEquals(false, summary.contains("累计"));
    }

    @Test
    void 上下文占用跨轮保留并供提示符使用() {
        TurnStats stats = new TurnStats();
        stats.reset();
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.CONTEXT_USAGE, "12.3k / 50k"));

        assertEquals("12.3k/50k", stats.contextLabel());

        stats.reset();
        assertEquals("12.3k/50k", stats.contextLabel(), "新的一轮不该把上下文占用清掉");
    }

    @Test
    void 本轮小结带上上下文占用() {
        TurnStats stats = new TurnStats();
        stats.reset();
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.USAGE, "+2591 / 累计 2591 / 上限 1000000"));
        stats.observe(ProgressEvent.of(ProgressEvent.Kind.CONTEXT_USAGE, "100/50k"));

        String summary = stats.summary();

        assertTrue(summary.contains("/ 上下文 100/50k /"), summary);
        assertTrue(summary.startsWith("—— 本轮 1 次模型调用 / +2591 tokens"), summary);
    }
}
