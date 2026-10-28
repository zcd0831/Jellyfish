package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话作用域工具白名单：fork 子会话、结论交付轮、重划调用共用的一道闸门。
 * 规则出处：08-application §8.6.1（SessionToolScope）、§8.10（越权返失败结果）。
 */
public final class SessionToolScope {

    /** 连续越权到这个次数就结束本次会话：模型会反复撞同一堵墙，白烧轮次。 */
    private static final int MAX_DENIALS = 3;

    private final Map<SessionId, Set<ToolId>> allowed = new ConcurrentHashMap<SessionId, Set<ToolId>>();
    private final Map<SessionId, Integer> denials = new ConcurrentHashMap<SessionId, Integer>();

    /** 声明本会话只能用这些工具（空集 = 一个都不许用）。 */
    public void restrict(SessionId sessionId, Set<ToolId> toolIds) {
        allowed.put(sessionId, Collections.unmodifiableSet(new HashSet<ToolId>(toolIds)));
        denials.remove(sessionId);
    }

    /** 解除限制：主循环与普通子代理不受白名单约束。 */
    public void clear(SessionId sessionId) {
        allowed.remove(sessionId);
        denials.remove(sessionId);
    }

    /** 本会话是否被显式授权了这个工具（授权优先于“代理绑定”）。 */
    public boolean grants(SessionId sessionId, ToolId toolId) {
        Set<ToolId> scope = allowed.get(sessionId);
        return scope != null && scope.contains(toolId);
    }

    /** 没有声明就是“不限制”；一旦声明就 fail-closed。 */
    public boolean allows(SessionId sessionId, ToolId toolId) {
        Set<ToolId> scope = allowed.get(sessionId);
        return scope == null || scope.contains(toolId);
    }

    public void recordDenial(SessionId sessionId) {
        Integer count = denials.get(sessionId);
        denials.put(sessionId, count == null ? 1 : count + 1);
    }

    public boolean exceeded(SessionId sessionId) {
        Integer count = denials.get(sessionId);
        return count != null && count >= MAX_DENIALS;
    }
}
