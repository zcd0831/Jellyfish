package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

/**
 * `spawn_subagents` 的一项请求。规则出处：模块-行动 §3.1（内置工具表）。
 */
public final class SubagentRequest {

    private final AgentId agentId;
    private final ModelId modelId;
    private final String task;

    public SubagentRequest(AgentId agentId, ModelId modelId, String task) {
        this.agentId = agentId;
        this.modelId = modelId;
        this.task = task;
    }

    public AgentId agentId() {
        return agentId;
    }

    public ModelId modelId() {
        return modelId;
    }

    public String task() {
        return task;
    }
}
