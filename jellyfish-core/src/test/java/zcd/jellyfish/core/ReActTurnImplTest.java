package zcd.jellyfish.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReActTurnImpl} 取消令牌部分的单元测试。
 * <p>
 * 这里只钉一件事：<b>取消回调的「恰好一次」语义</b>。它看着像细节，但两条路径
 * （注册时已取消 / 取消时已注册）都可能先到，而工具侧的回调是「杀掉在跑的子进程」这类动作——
 * 重复执行虽然多半无害，却会让「它到底会不会被执行」变成一条需要推理的约定。
 *
 * @author zcd
 */
@DisplayName("ReActTurnImpl 取消令牌")
class ReActTurnImplTest {

    @Test
    @DisplayName("新建回合的令牌未取消")
    void newTurn_should_notBeCancelled() {
        ReActTurnImpl turn = new ReActTurnImpl();

        assertFalse(turn.isCancelled());
    }

    @Test
    @DisplayName("取消后注册的回调应立即执行")
    void onCancel_should_runImmediately_whenAlreadyCancelled() {
        // Given
        ReActTurnImpl turn = new ReActTurnImpl();
        AtomicInteger calls = new AtomicInteger();

        // When：一个刚启动的长调用会这么写；若不立即执行，它会直接卡到自己的超时
        turn.cancel();
        turn.onCancel(calls::incrementAndGet);

        // Then
        assertTrue(turn.isCancelled());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("注册在先、取消在后时应由取消触发")
    void cancel_should_runRegisteredCallback() {
        // Given
        ReActTurnImpl turn = new ReActTurnImpl();
        AtomicInteger calls = new AtomicInteger();
        turn.onCancel(calls::incrementAndGet);

        // When
        turn.cancel();

        // Then
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("重复取消不得重复执行回调")
    void cancel_should_runCallbackOnce() {
        // Given
        ReActTurnImpl turn = new ReActTurnImpl();
        AtomicInteger calls = new AtomicInteger();
        turn.onCancel(calls::incrementAndGet);

        // When：Esc 可能被连按，回合结束时的兜底也可能再调一次
        turn.cancel();
        turn.cancel();
        turn.cancel();

        // Then
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("多个回调按注册顺序全部执行")
    void cancel_should_runAllCallbacks() {
        // Given
        ReActTurnImpl turn = new ReActTurnImpl();
        StringBuilder order = new StringBuilder();
        turn.onCancel(() -> order.append('a'));
        turn.onCancel(() -> order.append('b'));

        // When
        turn.cancel();

        // Then
        assertEquals("ab", order.toString());
    }

    @Test
    @DisplayName("单个回调抛错不得影响其余回调")
    void cancel_should_isolateCallbackFailure() {
        // Given
        ReActTurnImpl turn = new ReActTurnImpl();
        AtomicInteger survived = new AtomicInteger();
        turn.onCancel(() -> {
            throw new IllegalStateException("boom");
        });
        turn.onCancel(survived::incrementAndGet);

        // When：回调跑在渲染线程上，一个抛错不能把其余的与流句柄的取消一起带走
        turn.cancel();

        // Then
        assertEquals(1, survived.get());
    }
}
