package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.port.CancellationRegistry;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 在跑会话的终止信号登记表：一个会话一个令牌，父子用 parentSessionId 组树。
 * <p>只活在进程内——「在跑」不是持久事实（04-perception §7.1 规则 7），进程退出即散。
 * <p>规则出处：08-application §8.9.4.1（四个来源、两张表）、§8.11（终止的作用域）。
 */
public final class SessionCancellations implements CancellationRegistry {

    private final Map<SessionId, Signal> signals = new ConcurrentHashMap<SessionId, Signal>();
    /** 父会话 id → 子会话 id 集合；登记与级联都走它。 */
    private final Map<SessionId, Set<SessionId>> children = new ConcurrentHashMap<SessionId, Set<SessionId>>();
    private final Map<SessionId, SessionId> parents = new ConcurrentHashMap<SessionId, SessionId>();

    /**
     * 运行开始时登记。父已终止则子令牌立刻终止——否则「spawn 刚发出、父已被终止」这一瞬会漏掉。
     */
    public CancellationToken register(SessionId sessionId, SessionId parentSessionId) {
        Signal signal = new Signal();
        signals.put(sessionId, signal);
        if (parentSessionId != null) {
            parents.put(sessionId, parentSessionId);
            childrenOf(parentSessionId).add(sessionId);
            Signal parent = signals.get(parentSessionId);
            if (parent != null && parent.isCancelled()) {
                signal.cancel();
            }
        }
        return signal;
    }

    /** 运行结束（含异常路径）注销，顺手把自己从父集合里摘掉，避免长进程里集合只增不减。 */
    public void unregister(SessionId sessionId) {
        signals.remove(sessionId);
        children.remove(sessionId);
        SessionId parent = parents.remove(sessionId);
        Set<SessionId> siblings = parent == null ? null : children.get(parent);
        if (siblings != null) {
            siblings.remove(sessionId);
        }
    }

    /** 幂等：不在跑就没有令牌，但名下的子会话照样级联（它们可能还在跑）。 */
    public void cancel(SessionId sessionId) {
        if (sessionId == null) {
            return;
        }
        Signal signal = signals.get(sessionId);
        if (signal != null) {
            signal.cancel();
        }
        Set<SessionId> kids = children.get(sessionId);
        if (kids == null) {
            return;
        }
        for (SessionId child : new ArrayList<SessionId>(kids)) {
            cancel(child);
        }
    }

    /** 进程退出时尽力而为：正常退出在宽限期内叫停所有在途调用。 */
    public void cancelAll() {
        for (SessionId sessionId : new ArrayList<SessionId>(signals.keySet())) {
            cancel(sessionId);
        }
    }

    @Override
    public CancellationToken forSession(SessionId sessionId) {
        Signal signal = sessionId == null ? null : signals.get(sessionId);
        return signal == null ? CancellationToken.never() : signal;
    }

    private Set<SessionId> childrenOf(SessionId parentId) {
        Set<SessionId> existing = children.get(parentId);
        if (existing != null) {
            return existing;
        }
        Set<SessionId> created = Collections.newSetFromMap(new ConcurrentHashMap<SessionId, Boolean>());
        Set<SessionId> raced = children.putIfAbsent(parentId, created);
        return raced == null ? created : raced;
    }

    /** 令牌 = 一个布尔 + 回调；与外壳那个语义相同，只是来源换成超时或级联。 */
    private static final class Signal implements CancellationToken {

        private final List<Runnable> callbacks = new CopyOnWriteArrayList<Runnable>();
        private volatile boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public Runnable onCancel(final Runnable callback) {
            callbacks.add(callback);
            if (cancelled) {
                callback.run();
            }
            return new Runnable() {
                @Override
                public void run() {
                    callbacks.remove(callback);
                }
            };
        }

        @Override
        public void throwIfCancelled() {
            if (cancelled) {
                throw new java.util.concurrent.CancellationException("本轮已被终止");
            }
        }

        void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            for (Runnable callback : callbacks) {
                callback.run();
            }
        }
    }
}
