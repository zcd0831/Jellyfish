package zcd.jellyfish.cli.console;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.ReActResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CliReActListener} 的单元测试：钉住「回答走 stdout、诊断走 stderr」这条契约。
 *
 * @author zcd
 */
class CliReActListenerTest {

    @Test
    void onText_should_buffer_until_complete_then_write_to_stdout_verbatim() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText("你好");
        listener.onText("，世界");

        assertEquals("", console.out());
        listener.onComplete(ReActResult.completed("s1", "你好，世界", 1));
        assertEquals("你好，世界\n", console.out());
        assertEquals("", console.err());
    }

    @Test
    void onText_should_ignore_null_and_empty_delta() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText(null);
        listener.onText("");
        listener.onComplete(ReActResult.completed("s1", "", 1));

        assertEquals("", console.out());
    }

    @Test
    void onThinking_should_be_dropped_when_not_enabled() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onThinking("我在想");

        assertEquals("", console.out());
        assertEquals("", console.err());
    }

    @Test
    void onThinking_should_write_prefixed_line_when_enabled() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, true);

        listener.onThinking("我在想");
        listener.onThinking("一个问题");

        assertEquals("", console.out());
        assertEquals("· 我在想一个问题", console.err());
    }

    @Test
    void onThinking_should_restart_prefix_after_newline() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, true);

        listener.onThinking("第一行\n");
        listener.onThinking("第二行");

        assertEquals("· 第一行\n· 第二行", console.err());
    }

    @Test
    void onText_should_close_open_thinking_line() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, true);

        listener.onThinking("想完了");
        listener.onText("回答");

        assertEquals("· 想完了\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onToolCallStarted_should_write_single_diagnostic_line_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onToolCallStarted("call-1", "read_file");

        assertEquals("", console.out());
        assertEquals("→ read_file\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_report_status_and_length_without_full_output() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onToolCallCompleted("call-1", "read_file", true, "0123456789");

        assertEquals("", console.out());
        assertEquals("← read_file 完成（10 字符）\n", console.err());
        assertFalse(console.err().contains("0123456789"));
    }

    @Test
    void onToolCallCompleted_should_report_failure_and_zero_length_when_output_null() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onToolCallCompleted("call-1", "bash", false, null);

        assertEquals("← bash 失败（0 字符）\n", console.err());
    }

    @Test
    void onToolCallStarted_should_close_open_thinking_line_first() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, true);

        listener.onThinking("准备调工具");
        listener.onToolCallStarted("call-1", "bash");

        assertEquals("· 准备调工具\n→ bash\n", console.err());
    }

    @Test
    void onComplete_should_append_newline_when_answer_unterminated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText("没有换行的回答");
        listener.onComplete(ReActResult.completed("s1", "没有换行的回答", 1));

        assertEquals("没有换行的回答\n", console.out());
    }

    @Test
    void onComplete_should_not_append_newline_when_answer_already_terminated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText("有换行\n");
        listener.onComplete(ReActResult.completed("s1", "有换行\n", 1));

        assertEquals("有换行\n", console.out());
    }

    @Test
    void onComplete_should_not_print_content_again() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText("回答");
        listener.onComplete(ReActResult.completed("s1", "回答", 1));

        assertEquals("回答\n", console.out());
    }

    @Test
    void onComplete_should_write_result_content_when_truncated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onComplete(ReActResult.truncated("s1", "达到上限", 16));

        assertTrue(console.err().contains("已达最大轮次"));
        assertEquals("达到上限\n", console.out());
    }

    @Test
    void onToolCallStarted_should_move_buffered_text_to_stderr_as_trace() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText("我先看一下文件。");
        listener.onToolCallStarted("call-1", "read_file");

        assertEquals("… 我先看一下文件。\n→ read_file\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onComplete_should_write_only_final_round_text_when_tool_round_preceded() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText("我先看一下文件。");
        listener.onToolCallStarted("call-1", "read_file");
        listener.onToolCallCompleted("call-1", "read_file", true, "ok");
        listener.onText("结论是两句话。");
        listener.onComplete(ReActResult.completed("s1", "结论是两句话。", 2));

        assertEquals("结论是两句话。\n", console.out());
    }

    @Test
    void onCancelled_should_move_buffered_text_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText("写了一半");
        listener.onCancelled();

        assertEquals("… 写了一半\n已取消。\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onError_should_move_buffered_text_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onText("写了一半");
        listener.onError(new JellyfishException("连接断开"));

        assertEquals("… 写了一半\n回合失败：连接断开\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onComplete_should_not_warn_when_not_truncated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onComplete(ReActResult.completed("s1", "ok", 1));

        assertEquals("", console.err());
    }

    @Test
    void onComplete_should_tolerate_null_result() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onComplete(null);

        assertEquals("", console.out());
        assertEquals("", console.err());
    }

    @Test
    void onCancelled_should_write_stderr_line() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onCancelled();

        assertEquals("已取消。\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onError_should_write_message_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onError(new JellyfishException("模型调用失败"));

        assertEquals("回合失败：模型调用失败\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onError_should_fall_back_to_class_name_when_message_missing() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onError(new JellyfishException());

        assertTrue(console.err().contains("JellyfishException"));
    }

    @Test
    void onError_should_tolerate_null_error() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false);

        listener.onError(null);

        assertEquals("回合失败：未知错误\n", console.err());
    }

    @Test
    void constructor_should_reject_null_console() {
        assertThrows(NullPointerException.class, () -> new CliReActListener(null, false));
    }
}
