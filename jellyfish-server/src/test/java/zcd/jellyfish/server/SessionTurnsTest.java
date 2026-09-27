package zcd.jellyfish.server;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.server.http.ApiException;

import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionTurns} 的并发约束：同一会话只能有一个在途回合。
 *
 * @author zcd
 */
class SessionTurnsTest {

    @Test
    void acquire_should_throw_conflict_when_same_session_already_held() {
        SessionTurns turns = new SessionTurns();
        turns.acquire("s1");

        ApiException error = assertThrows(ApiException.class, () -> turns.acquire("s1"));

        assertEquals(409, error.getStatus());
        assertEquals("TURN_IN_PROGRESS", error.getCode());
    }

    @Test
    void acquire_should_succeed_again_after_release() {
        SessionTurns turns = new SessionTurns();
        Semaphore slot = turns.acquire("s1");
        turns.release("s1", slot);

        Semaphore again = turns.acquire("s1");

        assertEquals(0, again.availablePermits());
        turns.release("s1", again);
    }

    @Test
    void acquire_should_allow_different_sessions_when_concurrent() {
        SessionTurns turns = new SessionTurns();

        Semaphore first = turns.acquire("s1");
        Semaphore second = turns.acquire("s2");

        assertEquals(0, first.availablePermits());
        assertEquals(0, second.availablePermits());
    }

    @Test
    void cancel_should_return_true_and_cancel_turn_when_bound() {
        SessionTurns turns = new SessionTurns();
        turns.acquire("s1");
        FakeTurn turn = new FakeTurn("t1");
        turns.bind("s1", turn);

        assertTrue(turns.cancel("s1"));
        assertTrue(turn.isCancelled());
    }

    @Test
    void cancel_should_return_false_when_no_turn_bound() {
        SessionTurns turns = new SessionTurns();

        assertFalse(turns.cancel("missing"));
    }

    @Test
    void cancel_should_return_false_when_bound_turn_already_done() {
        SessionTurns turns = new SessionTurns();
        turns.acquire("s1");
        FakeTurn turn = new FakeTurn("t1");
        turns.bind("s1", turn);
        turn.finish();

        assertFalse(turns.cancel("s1"));
    }

    @Test
    void release_should_clear_bound_turn_when_called() {
        SessionTurns turns = new SessionTurns();
        Semaphore slot = turns.acquire("s1");
        turns.bind("s1", new FakeTurn("t1"));

        turns.release("s1", slot);

        assertFalse(turns.hasActive("s1"));
    }

    /**
     * 测试用的回合句柄：只实现「在途 / 已结束 / 已取消」三个状态。
     *
     * @author zcd
     */
    private static final class FakeTurn implements ReActTurn {

        /** 回合标识。 */
        private final String turnId;

        /** 是否已结束。 */
        private boolean done;

        /** 是否已取消。 */
        private boolean cancelled;

        /**
         * 构造回合。
         *
         * @param turnId 回合标识
         */
        private FakeTurn(String turnId) {
            this.turnId = turnId;
        }

        @Override
        public String getTurnId() {
            return turnId;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public ReActResult await() {
            return ReActResult.cancelled("s", 0);
        }

        @Override
        public boolean isDone() {
            return done;
        }

        /**
         * 标记回合结束。
         */
        private void finish() {
            this.done = true;
        }

        /**
         * 判断是否已取消。
         *
         * @return 已取消返回 {@code true}
         */
        private boolean isCancelled() {
            return cancelled;
        }
    }
}
