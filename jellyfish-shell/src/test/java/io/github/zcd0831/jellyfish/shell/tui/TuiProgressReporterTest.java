package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 进度事件分流：结束类转普通行，其余走状态行（D12.1、D20-2）。 */
class TuiProgressReporterTest {

    private static String render(ProgressEvent.Kind kind) throws UnsupportedEncodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AnsiRenderer renderer = new AnsiRenderer(new PrintStream(bytes, true, "UTF-8"), 80);
        new TuiProgressReporter(renderer).emit(ProgressEvent.of(kind, "消息"));
        return bytes.toString("UTF-8");
    }

    @Test
    void 计划与步骤走常驻行() throws Exception {
        for (ProgressEvent.Kind kind : new ProgressEvent.Kind[]{ProgressEvent.Kind.PLAN_CREATED,
                ProgressEvent.Kind.STEP_STARTED, ProgressEvent.Kind.STEP_FINISHED}) {
            String text = render(kind);

            assertTrue(text.contains("消息"), "应打印消息：" + kind + text);
            assertTrue(text.endsWith("\n"), "计划/步骤必须常驻一行：" + kind);
        }
    }

    @Test
    void 工具开始与结束各占一行() throws Exception {
        String started = render(ProgressEvent.Kind.TOOL_STARTED);
        String finished = render(ProgressEvent.Kind.TOOL_FINISHED);

        assertTrue(started.contains("↳ 消息"), "工具开始要有前缀：" + started);
        assertTrue(finished.contains("✓ 消息"), "工具结束要有结果标记：" + finished);
    }

    @Test
    void 用量走状态行() throws Exception {
        String text = render(ProgressEvent.Kind.USAGE);

        assertTrue(text.contains("\r\u001B[2K"), "用量是易变的，走状态行：" + text);
        assertTrue(!text.endsWith("\n"));
    }

    @Test
    void 阶段类事件走状态行() throws Exception {
        String text = render(ProgressEvent.Kind.RETRY_SCHEDULED);

        assertTrue(text.contains("\r\u001B[2K"), "非结束类事件应原地覆盖状态行：" + text);
        assertTrue(!text.endsWith("\n"), "状态行不产生新行：" + text);
    }
}
