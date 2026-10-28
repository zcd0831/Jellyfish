package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ExternalToolPort;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentSource;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus;
import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSchema;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSource;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolStatus;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 工具执行的合法性 / 审批 / 分发 / 截断分支。规则出处：05-action §5.9.1。 */
class ToolExecutorTest {

    private final io.github.zcd0831.jellyfish.application.shared.port.ProgressPort progress =
            event -> { };


    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ExternalToolPort external = mock(ExternalToolPort.class);
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final HumanInteractionPort human = mock(HumanInteractionPort.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final SessionToolScope scope = new SessionToolScope();

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private ToolExecutor executor(InternalToolHandlers handlers) {
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        return new ToolExecutor(catalog, external, artifacts, human, policies, handlers, progress, scope);
    }

    private void givenTool(ToolDefinition tool) {
        when(catalog.tools()).thenReturn(Collections.singletonList(tool));
        when(catalog.agents()).thenReturn(Collections.singletonList(new AgentDefinition(AgentId.of("a"), "a",
                AgentSource.USER_DEFINED, new PromptTemplate("p", Collections.<String>emptyList()),
                AgentStatus.ACTIVE, Collections.singletonList(tool.id()), Collections.emptyList())));
    }

    private static ToolDefinition tool(String name, SideEffectLevel sideEffect, SourceType type,
                                       ToolStatus status, String schema) {
        return new ToolDefinition(ToolId.of(name), name, "说明", new ToolSchema(schema), null,
                new ToolSource(type, name), sideEffect, 1000, status);
    }

    private static Map<String, Object> args() {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("path", "/tmp/a");
        return args;
    }

    @Test
    void 工具不存在_不可用_未绑定都返回错误结果() {
        ToolDefinition deprecated = tool("t1", SideEffectLevel.READ_ONLY, SourceType.INTERNAL,
                ToolStatus.DEPRECATED, "{}");
        givenTool(deprecated);

        assertTrue(executor(InternalToolHandlers.empty()).execute(session, "nope", args(), RecordId.of("r"))
                .content().contains("工具不存在"));
        assertTrue(executor(InternalToolHandlers.empty()).execute(session, "t1", args(), RecordId.of("r"))
                .content().contains("非 ACTIVE"));
    }

    @Test
    void 参数不合法时不执行() {
        givenTool(tool("t1", SideEffectLevel.READ_ONLY, SourceType.INTERNAL, ToolStatus.ACTIVE,
                "{\"type\":\"object\",\"required\":[\"missing\"]}"));

        assertTrue(executor(InternalToolHandlers.empty()).execute(session, "t1", args(), RecordId.of("r"))
                .content().contains("参数不合法"));
    }

    @Test
    void 只读工具不需要审批_可写工具需要且被拒就返回拒绝结果() {
        givenTool(tool("t1", SideEffectLevel.REPEATABLE, SourceType.REST, ToolStatus.ACTIVE, "{}"));
        when(external.invoke(any(), anyMap())).thenReturn("结果");
        when(human.ask(any(Question.class))).thenReturn(Answer.answered("不同意"));

        assertTrue(executor(InternalToolHandlers.empty()).execute(session, "t1", args(), RecordId.of("r"))
                .content().contains("用户拒绝"));

        when(human.ask(any(Question.class))).thenReturn(Answer.answered("同意"));
        assertTrue(executor(InternalToolHandlers.empty()).execute(session, "t1", args(), RecordId.of("r"))
                .content().contains("结果"));
    }

    @Test
    void 外部工具抛错变成结果而不是异常() {
        givenTool(tool("t1", SideEffectLevel.READ_ONLY, SourceType.REST, ToolStatus.ACTIVE, "{}"));
        when(external.invoke(any(), anyMap())).thenThrow(new IllegalStateException("连不上"));

        assertTrue(executor(InternalToolHandlers.empty()).execute(session, "t1", args(), RecordId.of("r"))
                .content().contains("工具调用失败"));
    }

    @Test
    void 未知内置处理器如实回报() {
        givenTool(tool("t1", SideEffectLevel.READ_ONLY, SourceType.INTERNAL, ToolStatus.ACTIVE, "{}"));

        assertTrue(executor(InternalToolHandlers.empty()).execute(session, "t1", args(), RecordId.of("r"))
                .content().contains("尚未实现"));
    }

    @Test
    void ask_user走人工端口且取消时如实回报() {
        givenTool(tool("ask_user", SideEffectLevel.READ_ONLY, SourceType.INTERNAL, ToolStatus.ACTIVE,
                "{\"type\":\"object\",\"required\":[\"question\"]}"));
        Map<String, Object> askArgs = new LinkedHashMap<String, Object>();
        askArgs.put("question", "叫什么？");

        when(human.ask(any(Question.class))).thenReturn(Answer.answered("张三"));
        assertTrue(executor(InternalToolHandlers.empty())
                .execute(session, "ask_user", askArgs, RecordId.of("r")).content().contains("张三"));

        when(human.ask(any(Question.class))).thenReturn(Answer.cancelled());
        assertTrue(executor(InternalToolHandlers.empty())
                .execute(session, "ask_user", askArgs, RecordId.of("r")).content().contains("取消"));
    }

    @Test
    void 超长结果被截断并归档() {
        givenTool(tool("t1", SideEffectLevel.READ_ONLY, SourceType.REST, ToolStatus.ACTIVE, "{}"));
        when(external.invoke(any(), anyMap())).thenReturn(new String(new char[5000]).replace('\0', 'x'));
        when(artifacts.put(any(), any(), any())).thenReturn("/tmp/artifact.txt");

        ToolExecOutcome outcome = executor(InternalToolHandlers.empty())
                .execute(session, "t1", args(), RecordId.of("r"));

        assertTrue(outcome.isTruncated());
        assertTrue(outcome.resourceUri().contains("artifact"));
    }

    @Test
    void 内置处理器被调用() {
        givenTool(tool("t1", SideEffectLevel.READ_ONLY, SourceType.INTERNAL, ToolStatus.ACTIVE, "{}"));
        InternalToolHandlers handlers = new InternalToolHandlers(Collections.singletonList(
                new InternalToolHandlers.NamedHandler("t1", new InternalToolHandler() {
                    @Override
                    public ToolExecOutcome handle(Session session, Map<String, Object> args) {
                        return ToolExecOutcome.plain("处理器结果");
                    }
                })));

        assertTrue(executor(handlers).execute(session, "t1", args(), RecordId.of("r"))
                .content().contains("处理器结果"));
    }

    @Test
    void 本地能力走处理器注册表而不是出站调用() {
        givenTool(tool("list_dir", SideEffectLevel.READ_ONLY, SourceType.NATIVE, ToolStatus.ACTIVE, "{}"));
        InternalToolHandlers handlers = new InternalToolHandlers(Collections.singletonList(
                new InternalToolHandlers.NamedHandler("list_dir", new InternalToolHandler() {
                    @Override
                    public ToolExecOutcome handle(Session session, Map<String, Object> args) {
                        return ToolExecOutcome.plain("目录内容");
                    }
                })));

        assertTrue(executor(handlers).execute(session, "list_dir", args(), RecordId.of("r"))
                .content().contains("目录内容"));
    }

    @Test
    void 命中绊线的命令先问人() {
        ToolExecutor subject = executor(InternalToolHandlers.empty());
        givenTool(tool("run_command", SideEffectLevel.IRREVERSIBLE, SourceType.NATIVE, ToolStatus.ACTIVE, "{}"));
        when(human.ask(any(Question.class))).thenReturn(Answer.answered("不同意"));

        String out = subject.execute(session, "run_command", shellArgs("rm -rf /"), RecordId.of("r1")).content();

        assertTrue(out.contains("用户拒绝执行"));
        org.mockito.Mockito.verify(human).ask(
                org.mockito.ArgumentMatchers.argThat(q -> q.prompt().contains("递归删除系统目录或家目录")));
    }

    @Test
    void 未命中绊线的命令直接执行() {
        ToolExecutor subject = executor(InternalToolHandlers.empty());
        givenTool(tool("run_command", SideEffectLevel.IRREVERSIBLE, SourceType.NATIVE, ToolStatus.ACTIVE, "{}"));
        subject.execute(session, "run_command", shellArgs("mvn -o test"), RecordId.of("r1"));

        org.mockito.Mockito.verify(human, org.mockito.Mockito.never()).ask(any(Question.class));
    }

    private static Map<String, Object> shellArgs(String command) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("command", command);
        return args;
    }

    @Test
    void 首次写要问一次之后本会话不再问() {
        ToolExecutor subject = executor(InternalToolHandlers.empty());
        givenTool(tool("edit_file", SideEffectLevel.REPEATABLE, SourceType.NATIVE, ToolStatus.ACTIVE, "{}"));
        when(human.ask(any(Question.class))).thenReturn(Answer.answered("同意"));

        subject.execute(session, "edit_file", args("path", "a.txt"), RecordId.of("r1"));
        subject.execute(session, "edit_file", args("path", "b.txt"), RecordId.of("r2"));

        org.mockito.Mockito.verify(human, org.mockito.Mockito.times(1)).ask(any(Question.class));
    }

    @Test
    void 信任按会话隔离且不覆盖命令工具() {
        ToolExecutor subject = executor(InternalToolHandlers.empty());
        givenTool(tool("edit_file", SideEffectLevel.REPEATABLE, SourceType.NATIVE, ToolStatus.ACTIVE, "{}"));
        when(human.ask(any(Question.class))).thenReturn(Answer.answered("同意"));
        Session other = Session.create(SessionId.of("s2"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);

        subject.execute(session, "edit_file", args("path", "a.txt"), RecordId.of("r1"));
        subject.execute(other, "edit_file", args("path", "a.txt"), RecordId.of("r2"));

        org.mockito.Mockito.verify(human, org.mockito.Mockito.times(2)).ask(any(Question.class));
    }

    private static Map<String, Object> args(String key, String value) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put(key, value);
        return args;
    }

    @Test
    void 工具调用前后各发一条进度事件() {
        givenTool(tool("read_file", SideEffectLevel.READ_ONLY, SourceType.REST, ToolStatus.ACTIVE,
                "{\"type\":\"object\"}"));
        java.util.List<io.github.zcd0831.jellyfish.application.shared.ProgressEvent> events = new java.util.ArrayList<>();
        when(external.invoke(any(), anyMap())).thenReturn("内容");
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        ToolExecutor reporting = new ToolExecutor(catalog, external, artifacts, human, policies,
                InternalToolHandlers.empty(), events::add, scope);

        reporting.execute(session, "read_file", java.util.Collections.<String, Object>singletonMap("path", "/tmp/a"),
                io.github.zcd0831.jellyfish.domain.shared.RecordId.of("r1"));

        assertEquals(2, events.size());
        assertEquals(io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.TOOL_STARTED, events.get(0).kind());
        assertTrue(events.get(0).message().contains("read_file"));
        assertEquals(io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.TOOL_FINISHED, events.get(1).kind());
    }

    @Test
    void 白名单外的工具返回失败结果并计入越权() {
        givenTool(tool("read_file", SideEffectLevel.READ_ONLY, SourceType.NATIVE, ToolStatus.ACTIVE,
                "{}"));
        scope.restrict(session.id(), java.util.Collections.singleton(
                io.github.zcd0831.jellyfish.domain.shared.ToolId.of("submit_step")));

        io.github.zcd0831.jellyfish.application.action.ToolExecOutcome outcome =
                executor(InternalToolHandlers.empty()).execute(session, "read_file",
                        java.util.Collections.<String, Object>emptyMap(),
                        io.github.zcd0831.jellyfish.domain.shared.RecordId.of("r1"));

        assertTrue(outcome.content().contains("不可用"), outcome.content());
        assertFalse(scope.exceeded(session.id()), "一次越权还没到阈值");
        scope.recordDenial(session.id());
        scope.recordDenial(session.id());
        assertTrue(scope.exceeded(session.id()), "连续越权到阈值就要结束会话");
    }

    @Test
    void 白名单显式授权的工具不受代理绑定限制() {
        givenTool(tool("replan_plan", SideEffectLevel.READ_ONLY, SourceType.INTERNAL, ToolStatus.ACTIVE, "{}"));
        when(catalog.agents()).thenReturn(Collections.<AgentDefinition>emptyList());
        scope.restrict(session.id(), Collections.singleton(ToolId.of("replan_plan")));

        String content = executor(InternalToolHandlers.empty()).execute(session, "replan_plan",
                java.util.Collections.<String, Object>emptyMap(),
                io.github.zcd0831.jellyfish.domain.shared.RecordId.of("r1")).content();

        assertTrue(content.contains("尚未实现"), "过绑定这一关后才轮到处理器：" + content);
    }
}
