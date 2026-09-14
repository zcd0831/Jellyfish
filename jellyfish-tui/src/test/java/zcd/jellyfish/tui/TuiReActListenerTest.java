package zcd.jellyfish.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.ReActResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TuiReActListener} 的单元测试。
 * <p>
 * 关注点：每个回调往暂存区写了什么、以及清空时机凑在一起时会不会出现重复显示。
 * 这些断言不需要任何终端环境，也不需要启动 {@code react} 线程。
 *
 * @author zcd
 */
class TuiReActListenerTest {

    /** 暂存区。 */
    private InflightTurn inflight;

    /** 被测对象。 */
    private TuiReActListener listener;

    @BeforeEach
    void setUp() {
        inflight = new InflightTurn();
        listener = new TuiReActListener(inflight);
    }

    @Test
    @DisplayName("onText 追加正文")
    void onText_should_append_text() {
        listener.onText("你好");
        listener.onText("，世界");

        assertEquals("你好，世界", inflight.snapshot().getText());
    }

    @Test
    @DisplayName("onThinking 追加思考过程，且与正文分开")
    void onThinking_should_append_thinking_separately() {
        listener.onThinking("想一想");
        listener.onText("答案");

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals("想一想", snapshot.getThinking());
        assertEquals("答案", snapshot.getText());
    }

    @Test
    @DisplayName("空增量不置脏标记")
    void onText_should_ignore_blank_delta() {
        inflight.clearDirty();

        listener.onText(null);
        listener.onText("");

        assertFalse(inflight.isDirty());
    }

    @Test
    @DisplayName("onToolCallStarted 清空正文——本轮已落库，不清就会重复显示")
    void onToolCallStarted_should_clear_text() {
        listener.onText("我先看一下文件");
        listener.onThinking("思考");

        listener.onToolCallStarted("c1", "read_file");

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertTrue(snapshot.getText().isEmpty());
        assertTrue(snapshot.getThinking().isEmpty());
    }

    @Test
    @DisplayName("工具轨迹不进暂存区：它由会话消息投影得出，存第二份就是缓存")
    void onToolCallCompleted_should_not_store_anything() {
        listener.onText("正文");

        listener.onToolCallCompleted("c1", "read_file", true, "内容");

        assertEquals("正文", inflight.snapshot().getText());
    }

    @Test
    @DisplayName("onComplete 清空正文并标记正常收敛")
    void onComplete_should_finish_completed() {
        listener.onText("最终回答");

        listener.onComplete(ReActResult.completed("s1", "最终回答", 1));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals(InflightTurn.Outcome.COMPLETED, snapshot.getOutcome());
        assertTrue(snapshot.getText().isEmpty(), "正文已落库，暂存区必须清空以免重复显示");
        assertFalse(inflight.isRunning());
    }

    @Test
    @DisplayName("截断的 onComplete 标记为未收敛")
    void onComplete_should_finish_truncated() {
        listener.onComplete(ReActResult.truncated("s1", "已达上限", 8));

        assertEquals(InflightTurn.Outcome.TRUNCATED, inflight.snapshot().getOutcome());
    }

    @Test
    @DisplayName("结果为 null 时按正常收敛处理，不抛异常")
    void onComplete_should_tolerate_null_result() {
        listener.onComplete(null);

        assertEquals(InflightTurn.Outcome.COMPLETED, inflight.snapshot().getOutcome());
    }

    @Test
    @DisplayName("onCancelled 清空正文并标记中断")
    void onCancelled_should_finish_cancelled() {
        listener.onText("说了半句");

        listener.onCancelled();

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals(InflightTurn.Outcome.CANCELLED, snapshot.getOutcome());
        assertTrue(snapshot.getText().isEmpty());
    }

    @Test
    @DisplayName("onError 记录错误原因")
    void onError_should_finish_error_with_message() {
        listener.onError(new JellyfishException("连接超时"));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertEquals(InflightTurn.Outcome.ERROR, snapshot.getOutcome());
        assertEquals("连接超时", snapshot.getErrorMessage());
    }

    @Test
    @DisplayName("无消息的异常回退到类名，不显示 null")
    void onError_should_fall_back_to_simple_name() {
        listener.onError(new JellyfishException());

        assertEquals(JellyfishException.class.getSimpleName(), inflight.snapshot().getErrorMessage());
    }

    @Test
    @DisplayName("异常为 null 时给固定兜底文案")
    void onError_should_tolerate_null_error() {
        listener.onError(null);

        assertEquals("未知错误", inflight.snapshot().getErrorMessage());
    }

    @Test
    @DisplayName("典型回合：流式正文 → 工具调用 → 第二轮正文 → 收敛，全程不重复")
    void listener_should_not_duplicate_content_across_turn() {
        listener.onText("第一轮的话");
        listener.onToolCallStarted("c1", "read_file");
        // 第一轮的 assistant 消息此刻已落库，监听器清空后屏上只剩会话里的那一份
        assertTrue(inflight.snapshot().getText().isEmpty());

        listener.onText("第二轮的话");
        listener.onComplete(ReActResult.completed("s1", "第二轮的话", 2));

        InflightTurn.Snapshot snapshot = inflight.snapshot();
        assertTrue(snapshot.getText().isEmpty(), "收敛后暂存区必须为空，否则第二轮正文会显示两遍");
        assertEquals(InflightTurn.Outcome.COMPLETED, snapshot.getOutcome());
    }
}
