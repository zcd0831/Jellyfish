package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Agent 定义装载完成事件：agent 索引每次重建后广播，供诊断与 UI 展示「当前有哪些 agent」。
 * <p>
 * 事件表达的是「索引已重建」而不是「配置发生了变更」：本轮不做新旧快照 diff，
 * 因此首次装载与热更新拿到的是同一种事件。{@link #getAgentIds()} 可以为空——
 * 「一个 agent 都没配」同样是必须可见的状态（此时全部人员按 fail-open 放行）。
 * <p>
 * 与其它通知一样经 {@code EventChannel} 异步派发，属 best-effort，可丢弃。
 *
 * @author zcd
 */
public final class AgentsLoadedEvent extends AbstractJellyfishEvent {

    /** 内置默认 agent 的标识，尚未加载配置时为 {@code null}。 */
    private final String defaultAgentId;

    /** 本次装载到的 agentId 集合。 */
    private final Set<String> agentIds;

    /**
     * 构造进程级事件。
     *
     * @param defaultAgentId 内置默认 agent 的标识，可为 {@code null}（未加载配置时）
     * @param agentIds       本次装载到的 agentId 集合，可为 {@code null}
     */
    public AgentsLoadedEvent(String defaultAgentId, Set<String> agentIds) {
        super(null);
        this.defaultAgentId = defaultAgentId;
        this.agentIds = agentIds == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(agentIds));
    }

    /**
     * 获取默认 agentId。
     *
     * @return 内置默认 agent 的标识，尚未加载配置时为 {@code null}
     */
    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    /**
     * 获取本次装载到的 agentId 集合。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}
     */
    public Set<String> getAgentIds() {
        return agentIds;
    }
}
