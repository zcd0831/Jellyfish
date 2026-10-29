package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 终止登记表：一个会话一个令牌、父终止级联子、不跨会话串台。规则出处：08-application §8.9.4.1。 */
class SessionCancellationsTest {

    private final SessionCancellations cancellations = new SessionCancellations();

    @Test
    void 终止父会话级联到子会话() {
        SessionId parent = SessionId.of("main");
        SessionId child = SessionId.of("sub");
        cancellations.register(parent, null);
        CancellationToken childToken = cancellations.register(child, parent);

        cancellations.cancel(parent);

        assertTrue(childToken.isCancelled(), "父终止必须级联到子");
        assertTrue(cancellations.forSession(parent).isCancelled());
    }

    @Test
    void 终止不会串到别的会话族() {
        SessionId a = SessionId.of("a");
        SessionId b = SessionId.of("b");
        cancellations.register(a, null);
        CancellationToken other = cancellations.register(b, null);

        cancellations.cancel(a);

        assertFalse(other.isCancelled(), "取消按会话隔离：另一个会话族不受影响");
    }

    @Test
    void 父已终止时新登记的子令牌立刻终止() {
        SessionId parent = SessionId.of("main");
        cancellations.register(parent, null);
        cancellations.cancel(parent);

        CancellationToken late = cancellations.register(SessionId.of("late-sub"), parent);

        assertTrue(late.isCancelled(), "spawn 与终止撞车时不能漏掉新子会话");
    }

    @Test
    void 注销后不再参与级联且回调被摘掉() {
        SessionId parent = SessionId.of("main");
        SessionId child = SessionId.of("sub");
        cancellations.register(parent, null);
        CancellationToken childToken = cancellations.register(child, parent);
        AtomicInteger hits = new AtomicInteger();
        childToken.onCancel(new Runnable() {
            @Override
            public void run() {
                hits.incrementAndGet();
            }
        });

        cancellations.unregister(child);
        cancellations.cancel(parent);

        assertEquals(0, hits.get(), "已结束的会话不该再被级联叫停");
        assertFalse(cancellations.forSession(child).isCancelled(), "注销后查不到令牌");
    }

    @Test
    void 回调注销后不再触发() {
        SessionId session = SessionId.of("s");
        CancellationToken token = cancellations.register(session, null);
        AtomicInteger hits = new AtomicInteger();
        Runnable unsubscribe = token.onCancel(new Runnable() {
            @Override
            public void run() {
                hits.incrementAndGet();
            }
        });

        unsubscribe.run();
        cancellations.cancel(session);

        assertEquals(0, hits.get(), "长会话里已结束的调用必须能摘掉自己的回调");
    }

    @Test
    void 没有在跑的会话返回永不终止() {
        assertFalse(cancellations.forSession(SessionId.of("idle")).isCancelled());
        assertFalse(cancellations.forSession(null).isCancelled());
    }
}
