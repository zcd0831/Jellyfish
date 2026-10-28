package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.JsonText;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * `spawn_subagents`：唯一并发点；子代理**不得再开子代理**（深度恒为 1）。
 * 规则出处：05-action §5.4.1、§5.9.1、04-perception §4.8.1 规则 6。
 */
public final class SpawnSubagentsHandler implements InternalToolHandler {

    private final SubagentLauncher launcher;

    public SpawnSubagentsHandler(SubagentLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolExecOutcome handle(Session session, Map<String, Object> args) {
        if (session.parentSessionId() != null) {
            return ToolExecOutcome.plain("子代理不得再开子代理（会话深度恒为 1）");
        }
        Object raw = args.get("subagents");
        if (!(raw instanceof List)) {
            return ToolExecOutcome.plain("缺少 subagents 数组");
        }
        List<SubagentRequest> requests = new ArrayList<SubagentRequest>();
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                return ToolExecOutcome.plain("subagents 里的每一项都必须是对象");
            }
            Map<String, Object> fields = (Map<String, Object>) item;
            Object task = fields.get("task");
            if (task == null || String.valueOf(task).trim().isEmpty()) {
                return ToolExecOutcome.plain("子代理任务不能为空");
            }
            requests.add(new SubagentRequest(agentOf(fields), modelOf(fields), String.valueOf(task)));
        }
        List<SubagentResult> results = launcher.launch(session, requests);
        return ToolExecOutcome.plain(toJson(results));
    }

    private static AgentId agentOf(Map<String, Object> fields) {
        Object agentId = fields.get("agentId");
        return agentId == null ? null : AgentId.of(String.valueOf(agentId));
    }

    private static ModelId modelOf(Map<String, Object> fields) {
        Object modelId = fields.get("modelId");
        return modelId == null ? null : ModelId.of(String.valueOf(modelId));
    }

    private static String toJson(List<SubagentResult> results) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < results.size(); i++) {
            SubagentResult result = results.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"sessionId\":\"").append(result.sessionId() == null ? "" : result.sessionId().getValue())
                    .append("\",\"conclusion\":\"").append(JsonText.escape(result.conclusion())).append("\"}");
        }
        return out.append(']').toString();
    }
}
