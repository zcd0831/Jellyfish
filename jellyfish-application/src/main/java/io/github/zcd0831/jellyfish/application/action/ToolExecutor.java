package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ExternalToolPort;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具执行的唯一入口：合法性 → 入参校验 → 放行审批 → 按来源分发 → 截断归档。
 * 规则出处：模块-行动 §8.1。
 */
public final class ToolExecutor {

    private static final String ASK_USER = "ask_user";

    private final CatalogRepository catalog;
    private final ExternalToolPort externalTools;
    private final ArtifactStore artifacts;
    private final HumanInteractionPort human;
    private final PolicyRepository policies;
    private final InternalToolHandlers handlers;

    public ToolExecutor(CatalogRepository catalog, ExternalToolPort externalTools, ArtifactStore artifacts,
                        HumanInteractionPort human, PolicyRepository policies, InternalToolHandlers handlers) {
        this.catalog = catalog;
        this.externalTools = externalTools;
        this.artifacts = artifacts;
        this.human = human;
        this.policies = policies;
        this.handlers = handlers;
    }

    public ToolExecOutcome execute(Session session, String toolName, Map<String, Object> args, RecordId recordId) {
        RuntimePolicy policy = policies.load();
        ToolDefinition tool = findTool(toolName);
        if (tool == null) {
            return ToolExecOutcome.plain("工具不存在：" + toolName);
        }
        if (!tool.isActive()) {
            return ToolExecOutcome.plain("工具不可用（非 ACTIVE）：" + toolName);
        }
        if (!isBoundToAgent(session, tool.id())) {
            return ToolExecOutcome.plain("该代理未绑定工具：" + toolName);
        }
        List<String> violations = tool.validateArgs(args);
        if (!violations.isEmpty()) {
            return ToolExecOutcome.plain("参数不合法：" + join(violations));
        }
        if (needsApproval(tool, policy)) {
            Answer answer = human.ask(new Question(Question.Source.APPROVAL,
                    "工具 " + toolName + " 可能产生副作用，允许执行吗？", null));
            if (answer.kind() == Answer.Kind.CANCELLED
                    || !io.github.zcd0831.jellyfish.application.shared.Approvals.isApproved(answer.text())) {
                return ToolExecOutcome.plain("用户拒绝执行：" + toolName);
            }
        }
        String raw = dispatch(session, tool, args);
        return truncate(raw, session, recordId, policy);
    }

    private String dispatch(Session session, ToolDefinition tool, Map<String, Object> args) {
        if (tool.source().getType() == SourceType.INTERNAL) {
            if (ASK_USER.equals(tool.source().getEndpointOrIdentifier())) {
                Object question = args.get("question");
                Answer answer = human.ask(new Question(Question.Source.MAIN_SESSION,
                        question == null ? "（模型没有提供问题文本）" : String.valueOf(question), null));
                return answer.kind() == Answer.Kind.CANCELLED ? "用户取消了回答" : answer.text();
            }
            InternalToolHandler handler = handlers.find(tool.source().getEndpointOrIdentifier());
            if (handler == null) {
                // 其余内置工具的处理器随各自切片加入（S3/S5），本期如实回报
                return "工具尚未实现：" + tool.name();
            }
            return handler.handle(session, args).content();
        }
        try {
            return externalTools.invoke(tool, args);
        } catch (RuntimeException e) {
            // 工具自身失败不是领域状态，只是一种结果（§7.1 规则 3）
            return "工具调用失败：" + e.getMessage();
        }
    }

    private ToolExecOutcome truncate(String content, Session session, RecordId recordId, RuntimePolicy policy) {
        if (content == null) {
            return ToolExecOutcome.plain("");
        }
        int limit = policy.toolResultTruncateLength();
        if (limit <= 0 || content.length() <= limit) {
            return ToolExecOutcome.plain(content);
        }
        String uri = artifacts.put(session.id(), recordId, content.getBytes(StandardCharsets.UTF_8));
        return new ToolExecOutcome(content.substring(0, limit), true, uri);
    }

    private boolean needsApproval(ToolDefinition tool, RuntimePolicy policy) {
        return tool.sideEffect() != SideEffectLevel.READ_ONLY && !policy.isToolTrusted(tool.id());
    }

    private boolean isBoundToAgent(Session session, ToolId toolId) {
        for (AgentDefinition agent : catalog.agents()) {
            if (agent.id().equals(session.agentId())) {
                return agent.listBoundToolIds().contains(toolId);
            }
        }
        return false;
    }

    private ToolDefinition findTool(String name) {
        for (ToolDefinition tool : catalog.tools()) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append("；");
            }
            out.append(values.get(i));
        }
        return out.toString();
    }

    /** 供测试与后续切片复用：把一个工具名解析成定义。 */
    public List<String> activeToolNames() {
        List<String> names = new ArrayList<String>();
        for (ToolDefinition tool : catalog.tools()) {
            if (tool.isActive()) {
                names.add(tool.name());
            }
        }
        return names;
    }
}
