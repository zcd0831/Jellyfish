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
    void 步骤结束转普通行() throws Exception {
        String text = render(ProgressEvent.Kind.STEP_FINISHED);

        assertTrue(text.contains("· 消息"), "应打印消息：" + text);
        assertTrue(text.endsWith("\n"), "结束类事件应独占一行：" + text);
    }

    @Test
    void 其余事件走状态行() throws Exception {
        String text = render(ProgressEvent.Kind.RETRY_SCHEDULED);

        assertTrue(text.contains("\r\u001B[2K"), "非结束类事件应原地覆盖状态行：" + text);
        assertTrue(!text.endsWith("\n"), "状态行不产生新行：" + text);
    }
}
