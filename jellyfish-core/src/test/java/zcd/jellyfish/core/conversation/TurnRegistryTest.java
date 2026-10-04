package zcd.jellyfish.core.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.TurnCancelledEvent;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.ReActTurn;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TurnRegistry} 的并发约束、取消与「终态即归还」。
 *
 * @author zcd
 */
class TurnRegistryTest {

    /** 通知发布入口：取消事件的观察点。 */
    private EventPublisher events;

    private TurnRegistry turns;

    @BeforeEach
    void setUp() {
        events = mock(EventPublisher.class);
        turns = new TurnRegistry(events);
    }

    @Test
    void acquire_should_reject_second_slot_for_same_session() {
        turns.acquire("s1");

        assertThrows(TurnInProgressException.class, () -> turns.acquire("s1"));
    }

    @Test
    void acquire_should_allow_different_sessions_in_parallel() {
        TurnRegistry.Slot first = turns.acquire("s1");
        TurnRegistry.Slot second = turns.acquire("s2");

        turns.release("s1", first);
        turns.release("s2", second);
    }

    @Test
    void release_should_allow_reacquire() {
        TurnRegistry.Slot slot = turns.acquire("s1");
        turns.release("s1", slot);

        TurnRegistry.Slot again = turns.acquire("s1");
        turns.release("s1", again);
    }

    @Test
    void release_should_be_idempotent_so_permits_do_not_inflate() {
        TurnRegistry.Slot slot = turns.acquire("s1");
        turns.release("s1", slot);
        turns.release("s1", slot);

        TurnRegistry.Slot again = turns.acquire("s1");
        // 只归还了一次，因此第二个会话仍然拿不到
        assertThrows(TurnInProgressException.class, () -> turns.acquire("s1"));
        turns.release("s1", again);
    }

    @Test
    void acquire_should_reject_blank_session() {
        assertThrows(JellyfishException.class, () -> turns.acquire(null));
        assertThrows(JellyfishException.class, () -> turns.acquire("   "));
    }

    @Test
    void cancel_should_delegate_to_bound_turn() {
        turns.acquire("s1");
        ReActTurn turn = mock(ReActTurn.class);
        when(turn.isDone()).thenReturn(false);
        when(turn.getTurnId()).thenReturn("t-1");
        turns.bind("s1", turn);

        assertTrue(turns.cancel("s1"));
        verify(turn).cancel();
    }

    @Test
    void cancel_should_publish_cancelled_event_with_turn_id() {
        // Given：一个在途回合
        turns.acquire("s1");
        ReActTurn turn = mock(ReActTurn.class);
        when(turn.isDone()).thenReturn(false);
        when(turn.getTurnId()).thenReturn("t-1");
        turns.bind("s1", turn);

        // When
        assertTrue(turns.cancel("s1"));

        // Then：插件侧看得到「谁被打断了」，且能凭 turnId 关联那一轮的输出
        ArgumentCaptor<TurnCancelledEvent> captured = ArgumentCaptor.forClass(TurnCancelledEvent.class);
        verify(events).publish(captured.capture());
        assertEquals("s1", captured.getValue().getSessionId());
        assertEquals("t-1", captured.getValue().getTurnId());
    }

    @Test
    void cancel_should_not_publish_twice_for_same_turn() {
        // Given：回合已经取消，但还没跑到终态（ReActTurn.cancel 只置标志，isDone 仍为假）
        turns.acquire("s1");
        ReActTurn turn = mock(ReActTurn.class);
        when(turn.isDone()).thenReturn(false);
        when(turn.getTurnId()).thenReturn("t-1");
        turns.bind("s1", turn);

        // When：连按两次取消
        turns.cancel("s1");
        turns.cancel("s1");

        // Then：取消动作本身幂等，但事件只报一次——否则「打断了几次」这类计数会被算重
        verify(events, times(1)).publish(any(TurnCancelledEvent.class));
    }

    @Test
    void cancel_should_publish_again_for_next_turn_of_same_session() {
        // Given：一个回合被取消，并走到终态（槽位归还）
        TurnRegistry.Slot firstSlot = turns.acquire("s1");
        ReActTurn first = mock(ReActTurn.class);
        when(first.isDone()).thenReturn(false);
        when(first.getTurnId()).thenReturn("t-1");
        turns.bind("s1", first);
        turns.cancel("s1");
        turns.release("s1", firstSlot);

        // When：同一个会话的下一个回合又被取消
        turns.acquire("s1");
        ReActTurn second = mock(ReActTurn.class);
        when(second.isDone()).thenReturn(false);
        when(second.getTurnId()).thenReturn("t-2");
        turns.bind("s1", second);
        turns.cancel("s1");

        // Then：去重标记必须随槽位一起清掉，否则第二个回合的取消再也报不出来
        ArgumentCaptor<TurnCancelledEvent> captured = ArgumentCaptor.forClass(TurnCancelledEvent.class);
        verify(events, times(2)).publish(captured.capture());
        assertEquals("t-1", captured.getAllValues().get(0).getTurnId());
        assertEquals("t-2", captured.getAllValues().get(1).getTurnId());
    }

    @Test
    void cancel_should_not_publish_when_nothing_running() {
        // When：没有在途回合，或回合已经结束
        assertFalse(turns.cancel("missing"));
        turns.acquire("s1");
        ReActTurn turn = mock(ReActTurn.class);
        when(turn.isDone()).thenReturn(true);
        turns.bind("s1", turn);
        assertFalse(turns.cancel("s1"));

        // Then：没取消到任何东西就没有「被打断」可言
        verify(events, never()).publish(any(TurnCancelledEvent.class));
    }

    @Test
    void cancel_should_return_false_when_no_turn() {
        assertFalse(turns.cancel("missing"));
        assertFalse(turns.cancel(null));
    }

    @Test
    void cancel_should_return_false_when_turn_already_done() {
        turns.acquire("s1");
        ReActTurn turn = mock(ReActTurn.class);
        when(turn.isDone()).thenReturn(true);
        turns.bind("s1", turn);

        assertFalse(turns.cancel("s1"));
    }

    @Test
    void isRunning_should_reflect_bound_turn_state() {
        assertFalse(turns.isRunning("s1"));

        turns.acquire("s1");
        ReActTurn turn = mock(ReActTurn.class);
        when(turn.isDone()).thenReturn(false);
        turns.bind("s1", turn);

        assertTrue(turns.isRunning("s1"));
    }

    @Test
    void turnOf_should_expose_bound_turn() {
        turns.acquire("s1");
        ReActTurn turn = mock(ReActTurn.class);
        turns.bind("s1", turn);

        assertSame(turn, turns.turnOf("s1").get());
        assertFalse(turns.turnOf("missing").isPresent());
    }

    @Test
    void releasing_should_release_slot_on_complete() {
        assertReleasesOnTerminal(listener -> listener.onComplete(ReActResult.completed("s1", "x", 1)));
    }

    @Test
    void releasing_should_release_slot_on_cancelled() {
        assertReleasesOnTerminal(listener -> listener.onCancelled());
    }

    @Test
    void releasing_should_release_slot_on_blocked() {
        assertReleasesOnTerminal(listener -> listener.onBlocked("工作区不干净"));
    }

    @Test
    void releasing_should_release_slot_on_error() {
        assertReleasesOnTerminal(listener -> listener.onError(new JellyfishException("boom")));
    }

    @Test
    void releasing_should_release_slot_even_when_delegate_throws() {
        TurnRegistry.Slot slot = turns.acquire("s1");
        ReActListener throwing = new ReActListener() {
            @Override
            public void onComplete(ReActResult result) {
                throw new JellyfishException("shell failed");
            }
        };
        ReActListener wrapped = turns.releasing("s1", slot, throwing);

        assertThrows(JellyfishException.class, () -> wrapped.onComplete(ReActResult.completed("s1", "x", 1)));

        TurnRegistry.Slot probe = turns.acquire("s1");
        turns.release("s1", probe);
    }

    @Test
    void releasing_should_forward_non_terminal_events() {
        TurnRegistry.Slot slot = turns.acquire("s1");
        ReActListener delegate = mock(ReActListener.class);
        ReActListener wrapped = turns.releasing("s1", slot, delegate);

        wrapped.onText("a");
        wrapped.onThinking("b");
        wrapped.onToolCallStarted("c1", "bash");
        wrapped.onToolCallStarted("c1", "bash", java.util.Collections.<String, Object>singletonMap("command", "ls"));
        wrapped.onToolCallOutput("c1", "bash", "out");
        wrapped.onToolCallCompleted("c1", "bash", true, "ok",
                java.util.Collections.<String, Object>singletonMap("exit", 0));

        verify(delegate).onText("a");
        verify(delegate).onThinking("b");
        verify(delegate).onToolCallStarted("c1", "bash");
        verify(delegate).onToolCallStarted("c1", "bash",
                java.util.Collections.<String, Object>singletonMap("command", "ls"));
        verify(delegate).onToolCallOutput("c1", "bash", "out");
        verify(delegate).onToolCallCompleted("c1", "bash", true, "ok",
                java.util.Collections.<String, Object>singletonMap("exit", 0));
        // 未到终态：槽位仍占着
        assertThrows(TurnInProgressException.class, () -> turns.acquire("s1"));
    }

    @Test
    void releasing_should_tolerate_null_delegate() {
        TurnRegistry.Slot slot = turns.acquire("s1");
        ReActListener wrapped = turns.releasing("s1", slot, null);

        assertDoesNotThrow(() -> wrapped.onComplete(ReActResult.completed("s1", "x", 1)));
        TurnRegistry.Slot probe = turns.acquire("s1");
        turns.release("s1", probe);
    }

    /**
     * 断言：某个终态回调之后槽位被归还。
     *
     * @param terminal 触发终态的调用
     */
    private void assertReleasesOnTerminal(java.util.function.Consumer<ReActListener> terminal) {
        TurnRegistry.Slot slot = turns.acquire("s1");
        ReActListener wrapped = turns.releasing("s1", slot, ReActListener.NOOP);

        terminal.accept(wrapped);

        TurnRegistry.Slot probe = turns.acquire("s1");
        turns.release("s1", probe);
    }
}
