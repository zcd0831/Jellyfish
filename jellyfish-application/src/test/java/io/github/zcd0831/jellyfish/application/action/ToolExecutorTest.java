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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 工具执行的合法性 / 审批 / 分发 / 截断分支。规则出处：模块-行动 §8.1。 */
class ToolExecutorTest {

    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ExternalToolPort external = mock(ExternalToolPort.class);
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final HumanInteractionPort human = mock(HumanInteractionPort.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private ToolExecutor executor(InternalToolHandlers handlers) {
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        return new ToolExecutor(catalog, external, artifacts, human, policies, handlers);
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
}
