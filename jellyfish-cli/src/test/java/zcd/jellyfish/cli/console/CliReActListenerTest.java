package zcd.jellyfish.cli.console;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.core.ReActResult;

import java.util.LinkedHashMap;
import java.util.Map;

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
        CliReActListener listener = new CliReActListener(console, false, false);

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
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onText(null);
        listener.onText("");
        listener.onComplete(ReActResult.completed("s1", "", 1));

        assertEquals("", console.out());
    }

    @Test
    void onThinking_should_be_dropped_when_not_enabled() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onThinking("我在想");

        assertEquals("", console.out());
        assertEquals("", console.err());
    }

    @Test
    void onThinking_should_write_prefixed_line_when_enabled() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, true, false);

        listener.onThinking("我在想");
        listener.onThinking("一个问题");

        assertEquals("", console.out());
        assertEquals("· 我在想一个问题", console.err());
    }

    @Test
    void onThinking_should_restart_prefix_after_newline() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, true, false);

        listener.onThinking("第一行\n");
        listener.onThinking("第二行");

        assertEquals("· 第一行\n· 第二行", console.err());
    }

    @Test
    void onText_should_close_open_thinking_line() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, true, false);

        listener.onThinking("想完了");
        listener.onText("回答");

        assertEquals("· 想完了\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onToolCallStarted_should_write_single_diagnostic_line_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onToolCallStarted("call-1", "read_file");

        assertEquals("", console.out());
        assertEquals("→ read_file\n", console.err());
    }

    @Test
    void onToolCallStarted_should_hide_arguments_by_default() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", "mvn test");

        listener.onToolCallStarted("call-1", "shell", arguments);

        assertEquals("→ shell\n", console.err());
    }

    @Test
    void onToolCallStarted_should_append_arguments_as_single_line_when_enabled() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, true);
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", "mvn -q test\n  && echo ok");
        arguments.put("cwd", "/x");

        listener.onToolCallStarted("call-1", "shell", arguments);

        // 参数里的换行被压平：一行就是一条记录，日志里不能被参数拆成好几段
        assertEquals("→ shell {\"command\": \"mvn -q test && echo ok\", \"cwd\": \"/x\"}\n", console.err());
    }

    @Test
    void onToolCallStarted_should_show_arguments_verbatim_when_enabled() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, true);
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("apiKey", "sk-secret");

        listener.onToolCallStarted("call-1", "call", arguments);

        // 与审批浮层、TUI 轨迹行同一份口径：参数原样显示（与 Codex 的 --verbose 同理，风险由使用者自担）
        assertEquals("→ call {\"apiKey\": \"sk-secret\"}\n", console.err());
    }

    @Test
    void onToolCallStarted_should_truncate_long_arguments_when_enabled() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, true);
        StringBuilder command = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            command.append('x');
        }
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", command.toString());

        listener.onToolCallStarted("call-1", "shell", arguments);

        // 一行有界：200 码点封顶（含省略号），否则一次 write_file 就能把整篇正文倒进日志
        String line = console.err().trim();
        assertEquals(200 + "→ shell ".length(), line.codePointCount(0, line.length()));
        assertTrue(line.endsWith("\u2026"), line);
    }

    @Test
    void onToolCallStarted_should_fall_back_to_name_when_arguments_empty() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, true);

        listener.onToolCallStarted("call-1", "shell", null);

        assertEquals("→ shell\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_report_status_and_length_without_full_output() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onToolCallCompleted("call-1", "read_file", true, "0123456789", null);

        assertEquals("", console.out());
        assertEquals("← read_file 完成（10 字符）\n", console.err());
        assertFalse(console.err().contains("0123456789"));
    }

    @Test
    void onToolCallCompleted_should_report_failure_and_zero_length_when_output_null() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onToolCallCompleted("call-1", "bash", false, null, null);

        assertEquals("← bash 失败（0 字符）\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_append_exit_code_when_command_failed() {
        // Given：命令跑了但退出码非零——命令行这边没有界面能画标记，只能写成文字
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(2));
        metadata.put(ToolMetadata.KEY_TERMINAL, ToolMetadata.TERMINAL_COMPLETED);

        // When
        listener.onToolCallCompleted("call-1", "shell", true, "构建失败", metadata);

        // Then
        assertEquals("← shell 完成（4 字符），退出码 2\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_append_summary_from_metadata() {
        // Given：子代理的结果正文不进屏幕（那是一整篇报告，而且已经回灌给模型了），
        // 命令行这边只靠这一句摘要回答「刚才那一步到底是什么」
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_SUMMARY, "子代理 scout · 3 轮 · 123456 tok");

        // When
        listener.onToolCallCompleted("call-1", "task", true, "[子代理 scout 已完成 · 3 轮]\n报告正文", metadata);

        // Then
        assertEquals("← task 完成（26 字符） · 子代理 scout · 3 轮 · 123456 tok\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_append_summary_before_outcome_suffix() {
        // Given
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_SUMMARY, "子代理 scout");
        metadata.put(ToolMetadata.KEY_TERMINAL, "REJECTED");

        // When
        listener.onToolCallCompleted("call-1", "task", true, "[子代理未开始]", metadata);

        // Then：摘要是「它是什么」的注解，终止原因是「它怎么了」的补充，各占各的位置
        assertEquals("← task 完成（8 字符） · 子代理 scout，REJECTED\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_not_repeat_failure_word_when_success_false() {
        // Given：异常路径：success=false 且 metadata 带 terminal=FAILED
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_TERMINAL, "FAILED");
        metadata.put(ToolMetadata.KEY_SUMMARY, "文件不存在: /x/y");

        // When
        listener.onToolCallCompleted("call-1", "read_file", false, "boom", metadata);

        // Then：只打一次「失败」，原因作为摘要出现，不再拼一个「，FAILED」
        assertEquals("← read_file 失败（4 字符） · 文件不存在: /x/y\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_keep_line_unchanged_when_no_summary() {
        // Given：普通工具不带摘要键
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("durationMs", 12L);

        // When
        listener.onToolCallCompleted("call-1", "read_file", true, "内容", metadata);

        // Then：不给普通工具多出一个空尾巴
        assertEquals("← read_file 完成（2 字符）\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_append_terminal_when_terminated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_TERMINAL, "TIMEOUT");

        listener.onToolCallCompleted("call-1", "shell", true, "部分输出", metadata);

        assertEquals("← shell 完成（4 字符），TIMEOUT\n", console.err());
    }

    @Test
    void onToolCallCompleted_should_stay_silent_when_outcome_is_normal() {
        // 成功与「零退出码」都不该在结束行上留下残迹
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(0));
        metadata.put(ToolMetadata.KEY_TERMINAL, ToolMetadata.TERMINAL_COMPLETED);
        metadata.put("durationMs", Long.valueOf(12L));

        listener.onToolCallCompleted("call-1", "shell", true, "一切正常", metadata);

        assertEquals("← shell 完成（4 字符）\n", console.err());
    }

    @Test
    void onToolCallStarted_should_close_open_thinking_line_first() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, true, false);

        listener.onThinking("准备调工具");
        listener.onToolCallStarted("call-1", "bash");

        assertEquals("· 准备调工具\n→ bash\n", console.err());
    }

    @Test
    void onComplete_should_append_newline_when_answer_unterminated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onText("没有换行的回答");
        listener.onComplete(ReActResult.completed("s1", "没有换行的回答", 1));

        assertEquals("没有换行的回答\n", console.out());
    }

    @Test
    void onComplete_should_not_append_newline_when_answer_already_terminated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onText("有换行\n");
        listener.onComplete(ReActResult.completed("s1", "有换行\n", 1));

        assertEquals("有换行\n", console.out());
    }

    @Test
    void onComplete_should_not_print_content_again() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onText("回答");
        listener.onComplete(ReActResult.completed("s1", "回答", 1));

        assertEquals("回答\n", console.out());
    }

    @Test
    void onComplete_should_write_result_content_when_truncated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onComplete(ReActResult.truncated("s1", "达到上限", 16));

        assertTrue(console.err().contains("已达最大轮次"));
        assertEquals("达到上限\n", console.out());
    }

    @Test
    void onToolCallStarted_should_move_buffered_text_to_stderr_as_trace() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onText("我先看一下文件。");
        listener.onToolCallStarted("call-1", "read_file");

        assertEquals("… 我先看一下文件。\n→ read_file\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onComplete_should_write_only_final_round_text_when_tool_round_preceded() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onText("我先看一下文件。");
        listener.onToolCallStarted("call-1", "read_file");
        listener.onToolCallCompleted("call-1", "read_file", true, "ok", null);
        listener.onText("结论是两句话。");
        listener.onComplete(ReActResult.completed("s1", "结论是两句话。", 2));

        assertEquals("结论是两句话。\n", console.out());
    }

    @Test
    void onCancelled_should_move_buffered_text_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onText("写了一半");
        listener.onCancelled();

        assertEquals("… 写了一半\n已取消。\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onError_should_move_buffered_text_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onText("写了一半");
        listener.onError(new JellyfishException("连接断开"));

        assertEquals("… 写了一半\n回合失败：连接断开\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onComplete_should_not_warn_when_not_truncated() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onComplete(ReActResult.completed("s1", "ok", 1));

        assertEquals("", console.err());
    }

    @Test
    void onComplete_should_tolerate_null_result() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onComplete(null);

        assertEquals("", console.out());
        assertEquals("", console.err());
    }

    @Test
    void onCancelled_should_write_stderr_line() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onCancelled();

        assertEquals("已取消。\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onError_should_write_message_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onError(new JellyfishException("模型调用失败"));

        assertEquals("回合失败：模型调用失败\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void onError_should_fall_back_to_class_name_when_message_missing() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onError(new JellyfishException());

        assertTrue(console.err().contains("JellyfishException"));
    }

    @Test
    void onError_should_tolerate_null_error() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onError(null);

        assertEquals("回合失败：未知错误\n", console.err());
    }

    @Test
    void onToolCallOutput_should_write_live_output_to_stderr() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onToolCallStarted("call-1", "bash");
        listener.onToolCallOutput("call-1", "bash", "hello\n");
        listener.onToolCallOutput("call-1", "bash", "world\n");

        // 工具名先出现一次，之后每行带同一个缩进；回答通道一根字节都没动
        assertTrue(console.err().contains("→ bash\n"), console.err());
        assertTrue(console.err().contains("  │ bash\n"), console.err());
        assertTrue(console.err().contains("  │ hello\n"), console.err());
        assertTrue(console.err().contains("  │ world\n"), console.err());
        assertEquals("", console.out());
    }

    @Test
    void onToolCallOutput_should_indent_only_once_per_line() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        // 同一行的两段（工具往往把一行拆成好几块写）只应得到一个缩进
        listener.onToolCallOutput("call-1", "bash", "a");
        listener.onToolCallOutput("call-1", "bash", "b\n");

        assertTrue(console.err().endsWith("  │ ab\n"), console.err());
        assertFalse(console.err().contains("  │ a  │ b"), console.err());
    }

    @Test
    void onToolCallOutput_should_close_half_line_before_tool_end() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        // 输出停在半行（命令的最后一行往往不带换行）：结束行不能紧贴在它后面
        listener.onToolCallOutput("call-1", "bash", "没有换行的尾巴");
        listener.onToolCallCompleted("call-1", "bash", true, "ok", null);

        assertTrue(console.err().contains("  │ 没有换行的尾巴\n← bash 完成"), console.err());
    }

    @Test
    void onToolCallOutput_should_ignore_null_and_empty_chunk() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onToolCallOutput("call-1", "bash", null);
        listener.onToolCallOutput("call-1", "bash", "");

        assertEquals("", console.err());
    }

    @Test
    void onToolCallOutput_should_start_new_block_when_tool_changes() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        CliReActListener listener = new CliReActListener(console, false, false);

        listener.onToolCallOutput("call-1", "bash", "a\n");
        listener.onToolCallOutput("call-2", "curl", "b\n");

        assertTrue(console.err().contains("  │ bash\n  │ a\n"), console.err());
        assertTrue(console.err().contains("  │ curl\n  │ b\n"), console.err());
    }

    @Test
    void constructor_should_reject_null_console() {
        assertThrows(NullPointerException.class, () -> new CliReActListener(null, false, false));
    }
}
