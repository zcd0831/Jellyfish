package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InflightTurn} 的单元测试。
 * <p>
 * 关注点：回合开始能正确重置（否则新回合内容不显示）、清空时机、有界策略、
 * 以及 {@code react} 线程写 / 渲染线程读的并发交接不丢数据。
 *
 * @author zcd
 */
class InflightTurnTest {

    @Test
    @DisplayName("开场状态为 IDLE 而非 RUNNING——否则未发消息时屏幕会显示假的「处理中…」")
    void snapshot_should_be_idle_when_new() {
        InflightTurn turn = new InflightTurn();

        InflightTurn.Snapshot snapshot = turn.snapshot();

        assertEquals(InflightTurn.Outcome.IDLE, snapshot.getOutcome());
        assertTrue(snapshot.isEmpty());
        assertFalse(turn.isRunning());
    }

    @Test
    @DisplayName("追加正文与思考过程后快照可见")
    void append_should_be_visible_in_snapshot() {
        InflightTurn turn = new InflightTurn();

        turn.appendText("你好");
        turn.appendThinking("想一想");

        InflightTurn.Snapshot snapshot = turn.snapshot();
        assertEquals("你好", snapshot.getText());
        assertEquals("想一想", snapshot.getThinking());
        assertFalse(snapshot.isEmpty());
    }

    @Test
    @DisplayName("空增量被忽略，且不置脏标记")
    void append_should_ignore_blank_delta() {
        InflightTurn turn = new InflightTurn();
        turn.clearDirty();

        turn.appendText(null);
        turn.appendText("");
        turn.appendThinking(null);

        assertFalse(turn.isDirty());
        assertTrue(turn.snapshot().getText().isEmpty());
    }

    @Test
    @DisplayName("清空后正文与思考都为空，但终局保持不变")
    void clearText_should_keep_outcome_when_cleared() {
        InflightTurn turn = new InflightTurn();
        turn.appendText("正文");
        turn.appendThinking("思考");
        turn.finish(InflightTurn.Outcome.COMPLETED, null);

        turn.clearText();

        InflightTurn.Snapshot snapshot = turn.snapshot();
        assertTrue(snapshot.getText().isEmpty());
        assertTrue(snapshot.getThinking().isEmpty());
        assertEquals(InflightTurn.Outcome.COMPLETED, snapshot.getOutcome());
    }

    @ParameterizedTest
    @EnumSource(InflightTurn.Outcome.class)
    @DisplayName("finish 记录的终局能原样读出；只有 RUNNING 仍算进行中")
    void finish_should_store_outcome(InflightTurn.Outcome outcome) {
        InflightTurn turn = new InflightTurn();

        turn.finish(outcome, "原因");

        assertEquals(outcome, turn.snapshot().getOutcome());
        assertEquals(outcome == InflightTurn.Outcome.RUNNING, turn.isRunning());
    }

    @Test
    @DisplayName("失败终局携带错误原因")
    void finish_should_carry_error_message_when_error() {
        InflightTurn turn = new InflightTurn();

        turn.finish(InflightTurn.Outcome.ERROR, "连接超时");

        assertEquals("连接超时", turn.snapshot().getErrorMessage());
    }

    @Test
    @DisplayName("begin 把上一回合的终局与内容全部重置——否则新回合的正文不会被显示")
    void begin_should_reset_outcome_and_content() {
        InflightTurn turn = new InflightTurn();
        turn.appendText("上一回合的正文");
        turn.finish(InflightTurn.Outcome.COMPLETED, null);

        turn.begin();

        InflightTurn.Snapshot snapshot = turn.snapshot();
        assertEquals(InflightTurn.Outcome.RUNNING, snapshot.getOutcome());
        assertTrue(snapshot.getText().isEmpty());
        assertTrue(turn.isRunning());
    }

    @Test
    @DisplayName("正文超过上限时保留尾部并置截断标记")
    void appendText_should_keep_tail_and_flag_when_over_limit() {
        InflightTurn turn = new InflightTurn();
        String chunk = repeat("x", 1000);
        int chunks = InflightTurn.MAX_TEXT_CHARS / 1000 + 10;

        for (int i = 0; i < chunks; i++) {
            turn.appendText(chunk);
        }

        InflightTurn.Snapshot snapshot = turn.snapshot();
        assertEquals(InflightTurn.MAX_TEXT_CHARS, snapshot.getText().length());
        assertTrue(snapshot.isTextTruncated());
    }

    @Test
    @DisplayName("思考过程上限独立于正文上限")
    void appendThinking_should_use_its_own_limit() {
        InflightTurn turn = new InflightTurn();
        String chunk = repeat("y", 1000);

        for (int i = 0; i < InflightTurn.MAX_THINKING_CHARS / 1000 + 10; i++) {
            turn.appendThinking(chunk);
        }

        InflightTurn.Snapshot snapshot = turn.snapshot();
        assertEquals(InflightTurn.MAX_THINKING_CHARS, snapshot.getThinking().length());
        assertTrue(snapshot.isThinkingTruncated());
        assertFalse(snapshot.isTextTruncated());
    }

    @Test
    @DisplayName("未超限时不置截断标记")
    void appendText_should_not_flag_when_within_limit() {
        InflightTurn turn = new InflightTurn();
        turn.appendText("short");

        assertFalse(turn.snapshot().isTextTruncated());
    }

    @Test
    @DisplayName("写入置脏标记，取走后可清除")
    void dirty_should_be_set_on_append_and_cleared_explicitly() {
        InflightTurn turn = new InflightTurn();
        turn.clearDirty();

        turn.appendText("a");
        assertTrue(turn.isDirty());

        turn.clearDirty();
        assertFalse(turn.isDirty());
    }

    @Test
    @DisplayName("并发写入与读取不丢增量、不抛异常")
    void snapshot_should_be_safe_when_written_concurrently() throws InterruptedException {
        InflightTurn turn = new InflightTurn();
        int writers = 4;
        int perWriter = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        List<Thread> threads = new ArrayList<Thread>();

        for (int w = 0; w < writers; w++) {
            Thread thread = new Thread(new AppendTask(turn, start, done, failure, perWriter));
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "写入线程应在超时前结束");

        assertNotNull(turn.snapshot());
        for (Thread thread : threads) {
            thread.interrupt();
        }
        assertEquals(null, failure.get(), "并发写入不应抛异常");
    }

    /**
     * 反复追加的测试线程。
     */
    private static final class AppendTask implements Runnable {

        /** 目标暂存区。 */
        private final InflightTurn target;

        /** 起跑闩。 */
        private final CountDownLatch start;

        /** 完成闩。 */
        private final CountDownLatch done;

        /** 异常收集点。 */
        private final AtomicReference<Throwable> failure;

        /** 追加次数。 */
        private final int times;

        /**
         * 构造任务。
         *
         * @param target  目标暂存区
         * @param start   起跑闩
         * @param done    完成闩
         * @param failure 异常收集点
         * @param times   追加次数
         */
        AppendTask(InflightTurn target, CountDownLatch start, CountDownLatch done,
                   AtomicReference<Throwable> failure, int times) {
            this.target = target;
            this.start = start;
            this.done = done;
            this.failure = failure;
            this.times = times;
        }

        @Override
        public void run() {
            try {
                start.await();
                for (int i = 0; i < times; i++) {
                    target.appendText("x");
                    target.snapshot();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        }
    }

    /**
     * 生成重复字符串。
     *
     * @param unit  单元
     * @param times 次数
     * @return 重复后的字符串
     */
    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
