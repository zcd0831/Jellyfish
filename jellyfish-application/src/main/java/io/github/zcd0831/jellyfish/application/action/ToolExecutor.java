package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.local.CommandRules;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ExternalToolPort;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
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
 * 规则出处：05-action §5.9.1。
 */
public final class ToolExecutor implements ToolInvoker {

    private static final String ASK_USER = "ask_user";
    private static final String RUN_COMMAND = "run_command";

    private final CatalogRepository catalog;
    private final ExternalToolPort externalTools;
    private final ArtifactStore artifacts;
    private final HumanInteractionPort human;
    private final PolicyRepository policies;
    private final ProgressPort progress;
    /** 首次同意后进本会话信任清单的三个工具。 */
    private static final List<String> WRITES =
            java.util.Arrays.asList("edit_file", "write_file", "delete_file");

    /**
     * 本会话已同意的写入：这是**运行期状态**，不落盘、进程退出即失效——
     * 持久信任一条写权限是用户自己该做的决定，不该由一次点击替他做。
     */
    private final java.util.Map<io.github.zcd0831.jellyfish.domain.shared.SessionId,
            java.util.Set<String>> trustedWrites =
            new java.util.concurrent.ConcurrentHashMap<io.github.zcd0831.jellyfish.domain.shared.SessionId,
                    java.util.Set<String>>();
    private final InternalToolHandlers handlers;

    public ToolExecutor(CatalogRepository catalog, ExternalToolPort externalTools, ArtifactStore artifacts,
                        HumanInteractionPort human, PolicyRepository policies, InternalToolHandlers handlers,
                        ProgressPort progress) {
        this.catalog = catalog;
        this.externalTools = externalTools;
        this.artifacts = artifacts;
        this.human = human;
        this.policies = policies;
        this.progress = progress;
        this.handlers = handlers;
    }

    @Override
    public ToolExecOutcome execute(Session session, String toolName, Map<String, Object> args, RecordId recordId) {
        boolean reportable = !ASK_USER.equals(toolName);
        long startedAt = System.currentTimeMillis();
        if (reportable) {
            progress.emit(ProgressEvent.of(ProgressEvent.Kind.TOOL_STARTED, toolName + "（" + summarize(args) + "）"));
        }
        ToolExecOutcome outcome = executeTool(session, toolName, args, recordId);
        if (reportable) {
            long elapsed = System.currentTimeMillis() - startedAt;
            // 结果对象没有成功/失败标志（失败也是结果），只报"完成 + 结果首行"，不臆断成败
            progress.emit(ProgressEvent.of(ProgressEvent.Kind.TOOL_FINISHED,
                    toolName + " 完成 " + elapsed + "ms：" + firstLine(outcome.content(), 60)));
        }
        return outcome;
    }

    /** 参数摘要只取一眼能看懂的部分，别把整段 JSON 塞进界面。 */
    private static String summarize(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "无参数";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            String value = String.valueOf(entry.getValue());
            text.append(entry.getKey()).append('=').append(firstLine(value, 40));
            if (text.length() > 100) {
                break;
            }
        }
        return text.toString();
    }

    private static String firstLine(String value, int limit) {
        if (value == null) {
            return "";
        }
        int nl = value.indexOf('\n');
        String line = nl < 0 ? value : value.substring(0, nl);
        return line.length() > limit ? line.substring(0, limit) + "…" : line;
    }

    private ToolExecOutcome executeTool(Session session, String toolName, Map<String, Object> args, RecordId recordId) {
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
        String refusal = approve(session, tool, args, policy);
        if (refusal != null) {
            return ToolExecOutcome.plain(refusal);
        }
        String raw = dispatch(session, tool, args);
        return truncate(raw, session, recordId, policy);
    }

    /**
     * `INTERNAL` / `NATIVE` / `PLUGIN` 共用同一张处理器注册表，只有 `REST` / `MCP` 才是出站调用。
     * 规则出处：05-action §5.6（ToolSource）、§5.9.1 分发图、§5.8.1 规则 3/4。
     */
    private String dispatch(Session session, ToolDefinition tool, Map<String, Object> args) {
        SourceType source = tool.source().getType();
        if (source == SourceType.INTERNAL || source == SourceType.NATIVE || source == SourceType.PLUGIN) {
            if (source == SourceType.INTERNAL
                    && ASK_USER.equals(tool.source().getEndpointOrIdentifier())) {
                Object question = args.get("question");
                Answer answer = human.ask(new Question(Question.Source.MAIN_SESSION,
                        question == null ? "（模型没有提供问题文本）" : String.valueOf(question), null));
                return answer.kind() == Answer.Kind.CANCELLED ? "用户取消了回答" : answer.text();
            }
            InternalToolHandler handler = handlers.find(tool.source().getEndpointOrIdentifier());
            if (handler == null) {
                return "工具尚未实现：" + tool.name();
            }
            return handler.handle(session, args).content();
        }
        try {
            return externalTools.invoke(tool, args);
        } catch (RuntimeException e) {
            // 工具自身失败不是领域状态，只是一种结果（05-action §5.8.1 规则 3）
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

    /** 返回 null 表示可以继续；否则是"用户拒绝执行"的结果内容。 */
    private String approve(Session session, ToolDefinition tool, Map<String, Object> args, RuntimePolicy policy) {
        String question = approvalQuestion(session, tool, args, policy);
        if (question == null) {
            return null;
        }
        Answer answer = human.ask(new Question(Question.Source.APPROVAL, question, null));
        boolean approved = answer.kind() != Answer.Kind.CANCELLED
                && io.github.zcd0831.jellyfish.application.shared.Approvals.isApproved(answer.text());
        if (!approved) {
            return "用户拒绝执行：" + tool.name();
        }
        if (isWorkspaceWrite(tool)) {
            trustedWritesOf(session).addAll(WRITES);
        }
        return null;
    }

    private boolean isWorkspaceWrite(ToolDefinition tool) {
        return tool.source().getType() == SourceType.NATIVE && WRITES.contains(tool.id().getValue());
    }

    private java.util.Set<String> trustedWritesOf(Session session) {
        java.util.Set<String> trusted = trustedWrites.get(session.id());
        if (trusted != null) {
            return trusted;
        }
        java.util.Set<String> created =
                java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        java.util.Set<String> existing = trustedWrites.putIfAbsent(session.id(), created);
        return existing == null ? created : existing;
    }

    /**
     * 规则 5 管一般工具；`run_command` 例外，改由绊线判定——
     * 只有它执行的是 shell 字符串，其余本地工具的参数都是结构化路径。
     */
    private String approvalQuestion(Session session, ToolDefinition tool, Map<String, Object> args,
                                    RuntimePolicy policy) {
        if (isShellCommand(tool)) {
            Object command = args.get("command");
            String line = command == null ? "" : String.valueOf(command);
            CommandRules.Verdict verdict = CommandRules.assess(line);
            if (verdict.allowed()) {
                return null;
            }
            return "命中高危规则，要执行这条命令吗？\n  原因：" + verdict.reason()
                    + "\n  命令：" + CommandRules.summarize(line);
        }
        if (tool.sideEffect() == SideEffectLevel.READ_ONLY || policy.isToolTrusted(tool.id())) {
            return null;
        }
        if (isWorkspaceWrite(tool)) {
            return trustedWritesOf(session).contains(tool.id().getValue()) ? null
                    : "允许本会话在此工作区内写入与删除文件吗？\n  工具：" + tool.name()
                    + "\n  同意后本次会话不再逐次询问（重启或换会话重新确认）";
        }
        return "工具 " + tool.name() + " 可能产生副作用，允许执行吗？";
    }

    private static boolean isShellCommand(ToolDefinition tool) {
        return tool.source().getType() == SourceType.NATIVE && RUN_COMMAND.equals(tool.id().getValue());
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
