package zcd.jellyfish.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.conversation.ShellTurnEvent;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TuiTurnListener} 的单元测试。
 * <p>
 * 关注点：每个回调往暂存区写了什么、以及清空时机凑在一起时会不会出现重复显示。
 * 这些断言不需要任何终端环境，也不需要启动 {@code react} 线程。
 *
 * @author zcd
 */
class TuiTurnListenerTest {

    /** 暂存区。 */
    private InflightTurn inflight;

    /** 被测对象。 */
    private TuiTurnListener listener;

    @BeforeEach
    void setUp() {
        inflight = new InflightTurn();
        listener = new TuiTurnListener(inflight);
    }

    @Test
    @DisplayName("onText 追加正文")
    void onText_should_append_text() {
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "你好"));
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "，世界"));

        assertEquals("你好，世界", inflight.snapshot().getText());
    }

    @Test
    @DisplayName("onThinking 追加思考过程，且与正文分开")
    void onThinking_should_append_thinking_separately() {
        listener.onTurnEvent(ShellTurnEvent.thinking("s1", "t1", "想一想"));
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "答案"));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals("想一想", snapshot.getThinking());
        assertEquals("答案", snapshot.getText());
    }

    @Test
    @DisplayName("空增量不置脏标记")
    void onText_should_ignore_blank_delta() {
        inflight.clearDirty();

        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", null));
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", ""));

        assertFalse(inflight.isDirty());
    }

    @Test
    @DisplayName("onToolCallStarted 清空正文——本轮已落库，不清就会重复显示")
    void onToolCallStarted_should_clear_text() {
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "我先看一下文件"));
        listener.onTurnEvent(ShellTurnEvent.thinking("s1", "t1", "思考"));

        listener.onTurnEvent(ShellTurnEvent.toolStarted("s1", "t1", "c1", "read_file", null));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertTrue(snapshot.getText().isEmpty());
        assertTrue(snapshot.getThinking().isEmpty());
    }

    @Test
    @DisplayName("带参数的工具开始把目标写进暂存区")
    void onToolCallStarted_should_capture_arguments() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "a.txt");

        listener.onTurnEvent(ShellTurnEvent.toolStarted("s1", "t1", "c1", "read_file", arguments));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals("read_file", snapshot.getRunningToolName());
        assertEquals(arguments, snapshot.getRunningToolArguments());
    }

    @Test
    @DisplayName("二参回调仍生效——它是兼容重载，不能静默变成空实现")
    void onToolCallStarted_without_arguments_should_still_record_name() {
        listener.onTurnEvent(ShellTurnEvent.toolStarted("s1", "t1", "c1", "bash", null));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals("bash", snapshot.getRunningToolName());
        assertTrue(snapshot.getRunningToolArguments().isEmpty());
    }

    @Test
    @DisplayName("工具轨迹不进暂存区：它由会话消息投影得出，存第二份就是缓存")
    void onToolCallCompleted_should_not_store_anything() {
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "正文"));

        listener.onTurnEvent(ShellTurnEvent.toolCompleted("s1", "t1", "c1", "read_file", true, "内容", null));

        assertEquals("正文", inflight.snapshot().getText());
    }

    @Test
    @DisplayName("onComplete 清空正文并标记正常收敛")
    void onComplete_should_finish_completed() {
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "最终回答"));

        listener.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "最终回答", 1, false));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals(InflightTurn.Outcome.COMPLETED, snapshot.getOutcome());
        assertTrue(snapshot.getText().isEmpty(), "正文已落库，暂存区必须清空以免重复显示");
        assertFalse(inflight.isRunning());
    }

    @Test
    @DisplayName("截断的 onComplete 标记为未收敛")
    void onComplete_should_finish_truncated() {
        listener.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "已达上限", 8, true));

        assertEquals(InflightTurn.Outcome.TRUNCATED, inflight.snapshot().getOutcome());
    }

    @Test
    @DisplayName("正文为 null 时按正常收敛处理，不抛异常")
    void onComplete_should_tolerate_null_content() {
        listener.onTurnEvent(ShellTurnEvent.completed("s1", "t1", null, 0, false));

        assertEquals(InflightTurn.Outcome.COMPLETED, inflight.snapshot().getOutcome());
    }

    @Test
    @DisplayName("收敛时带提示：终局仍是 COMPLETED，提示原样带进快照")
    void onComplete_should_carry_notice_when_present() {
        // 被输出上限截断 / 模型一个字都没回，都属于「收敛但有例外」——
        // 终局不能变成 TRUNCATED（那是「已达最大轮次」，文案完全不同）
        listener.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "半句话", 1, false,
                "回复被输出上限截断（结束原因：length）"));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals(InflightTurn.Outcome.COMPLETED, snapshot.getOutcome());
        assertEquals("回复被输出上限截断（结束原因：length）", snapshot.getNote());
    }

    @Test
    @DisplayName("收敛时无提示：快照里不留下上一回合的旧说明")
    void onComplete_should_leave_note_null_when_absent() {
        listener.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "正常答复", 1, false));

        assertEquals(InflightTurn.Outcome.COMPLETED, inflight.snapshot().getOutcome());
        assertNull(inflight.snapshot().getNote());
    }

    @Test
    @DisplayName("onCancelled 清空正文并标记中断")
    void onCancelled_should_finish_cancelled() {
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "说了半句"));

        listener.onTurnEvent(ShellTurnEvent.cancelled("s1", "t1"));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals(InflightTurn.Outcome.CANCELLED, snapshot.getOutcome());
        assertTrue(snapshot.getText().isEmpty());
    }

    @Test
    @DisplayName("onError 记录错误原因")
    void onError_should_finish_error_with_message() {
        listener.onTurnEvent(ShellTurnEvent.error("s1", "t1", new JellyfishException("连接超时")));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals(InflightTurn.Outcome.ERROR, snapshot.getOutcome());
        assertEquals("连接超时", snapshot.getNote());
    }

    @Test
    @DisplayName("无消息的异常回退到类名，不显示 null")
    void onError_should_fall_back_to_simple_name() {
        listener.onTurnEvent(ShellTurnEvent.error("s1", "t1", new JellyfishException()));

        assertEquals(JellyfishException.class.getSimpleName(), inflight.snapshot().getNote());
    }

    @Test
    @DisplayName("异常为 null 时给固定兜底文案")
    void onError_should_tolerate_null_error() {
        listener.onTurnEvent(ShellTurnEvent.error("s1", "t1", null));

        assertEquals("未知错误", inflight.snapshot().getNote());
    }

    @Test
    @DisplayName("onToolCallOutput 把实时输出写进暂存区")
    void onToolCallOutput_should_append_lines() {
        listener.onTurnEvent(ShellTurnEvent.toolStarted("s1", "t1", "c1", "bash", null));

        listener.onTurnEvent(ShellTurnEvent.toolOutput("s1", "t1", "c1", "bash", "第一行\n第二行\n"));

        assertEquals("bash", inflight.snapshot().getRunningToolName());
        assertEquals("第一行", inflight.snapshot().getToolOutputLines().get(0));
    }

    @Test
    @DisplayName("onToolCallOutput 置脏标记——不置的话渲染线程不会取这一帧")
    void onToolCallOutput_should_mark_dirty() {
        inflight.clearDirty();

        listener.onTurnEvent(ShellTurnEvent.toolOutput("s1", "t1", "c1", "bash", "x"));

        assertTrue(inflight.isDirty());
    }

    @Test
    @DisplayName("onToolCallCompleted 清掉实时输出——结果随后由会话投影渲染，留着就是两份")
    void onToolCallCompleted_should_clear_tool_output() {
        listener.onTurnEvent(ShellTurnEvent.toolStarted("s1", "t1", "c1", "bash", null));
        listener.onTurnEvent(ShellTurnEvent.toolOutput("s1", "t1", "c1", "bash", "输出\n"));

        listener.onTurnEvent(ShellTurnEvent.toolCompleted("s1", "t1", "c1", "bash", true, "输出", null));

        assertNull(inflight.snapshot().getRunningToolName());
        assertTrue(inflight.snapshot().getToolOutputLines().isEmpty());
    }

    @Test
    @DisplayName("回合终结时清掉实时输出")
    void onComplete_should_clear_tool_output() {
        listener.onTurnEvent(ShellTurnEvent.toolStarted("s1", "t1", "c1", "bash", null));
        listener.onTurnEvent(ShellTurnEvent.toolOutput("s1", "t1", "c1", "bash", "输出\n"));

        listener.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "答案", 1, false));

        assertTrue(inflight.snapshot().getToolOutputLines().isEmpty());
    }

    @Test
    @DisplayName("典型回合：流式正文 → 工具调用 → 第二轮正文 → 收敛，全程不重复")
    void listener_should_not_duplicate_content_across_turn() {
        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "第一轮的话"));
        listener.onTurnEvent(ShellTurnEvent.toolStarted("s1", "t1", "c1", "read_file", null));
        // 第一轮的 assistant 消息此刻已落库，监听器清空后屏上只剩会话里的那一份
        assertTrue(inflight.snapshot().getText().isEmpty());

        listener.onTurnEvent(ShellTurnEvent.text("s1", "t1", "第二轮的话"));
        listener.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "第二轮的话", 2, false));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertTrue(snapshot.getText().isEmpty(), "收敛后暂存区必须为空，否则第二轮正文会显示两遍");
        assertEquals(InflightTurn.Outcome.COMPLETED, snapshot.getOutcome());
    }
}
