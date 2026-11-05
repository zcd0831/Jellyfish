package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentSource;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus;
import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.action.model.ChatProtocol;
import io.github.zcd0831.jellyfish.domain.action.model.ContextLimit;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.ModelStatus;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.model.ProviderStatus;
import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSchema;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSource;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolStatus;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 会话开启的解析分支：agent/model 缺省、显式指定、不存在、不可用。 */
class StartSessionUseCaseTest {

    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final io.github.zcd0831.jellyfish.application.action.port.BudgetRepository budgets =
            mock(io.github.zcd0831.jellyfish.application.action.port.BudgetRepository.class);
    private final SessionTitleGenerator titles = mock(SessionTitleGenerator.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final Clock clock = mock(Clock.class);
    private final BudgetService budgetService = mock(BudgetService.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final io.github.zcd0831.jellyfish.application.planning.SessionCancellations cancellations =
            new io.github.zcd0831.jellyfish.application.planning.SessionCancellations();

    private StartSessionUseCase useCase() {
        when(clock.nowMillis()).thenReturn(1000L);
        when(catalog.providers()).thenReturn(Collections.singletonList(provider(ProviderStatus.ACTIVE)));
        when(titles.generate(any(Session.class), anyString())).thenReturn("标题");
        return new StartSessionUseCase(catalog, sessions, budgets, titles, new SystemPromptAssembler(),
                commitScope, clock, cancellations, eventPublisher);
    }

    private static AgentDefinition agent(String id, AgentStatus status) {
        return new AgentDefinition(AgentId.of(id), id, AgentSource.BUILT_IN,
                new PromptTemplate("提示词", Collections.<String>emptyList()), status,
                Collections.<ToolId>emptyList(), Collections.emptyList());
    }

    private static io.github.zcd0831.jellyfish.domain.action.model.Provider provider(ProviderStatus status) {
        return new io.github.zcd0831.jellyfish.domain.action.model.Provider(
                io.github.zcd0831.jellyfish.domain.shared.ProviderId.of("p1"), "p1", "http://localhost",
                io.github.zcd0831.jellyfish.domain.action.model.ChatProtocol.OPENAI_CHAT,
                new io.github.zcd0831.jellyfish.domain.action.model.ApiCredential("FAKE_KEY"), status);
    }

    private static Model model(String id, ModelStatus status) {
        return new Model(ModelId.of(id), io.github.zcd0831.jellyfish.domain.shared.ProviderId.of("p1"), "m",
                status, new ContextLimit(1000, 1000), Collections.emptyList());
    }

    private static ToolDefinition tool(ToolStatus status) {
        return new ToolDefinition(ToolId.of("t1"), "t1", "说明", new ToolSchema("{}"), null,
                new ToolSource(SourceType.INTERNAL, "t1"), SideEffectLevel.READ_ONLY, 1, status);
    }

    @Test
    void 缺省取内置主代理与第一个可调用模型() {
        AgentDefinition main = agent("builtin.main", AgentStatus.ACTIVE);
        when(catalog.agents()).thenReturn(Collections.singletonList(main));
        when(catalog.models()).thenReturn(Collections.singletonList(model("m1", ModelStatus.ACTIVE)));
        when(catalog.tools()).thenReturn(Collections.singletonList(tool(ToolStatus.ACTIVE)));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());

        Result<SessionId> result = useCase().start(null, null, "你好");

        assertTrue(result.isOk());
    }

    @Test
    void 会话开启提交后派发SessionStarted() {
        AgentDefinition main = agent("builtin.main", AgentStatus.ACTIVE);
        when(catalog.agents()).thenReturn(Collections.singletonList(main));
        when(catalog.models()).thenReturn(Collections.singletonList(model("m1", ModelStatus.ACTIVE)));
        when(catalog.tools()).thenReturn(Collections.singletonList(tool(ToolStatus.ACTIVE)));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());

        Result<SessionId> result = useCase().start(null, null, "你好");

        assertTrue(result.isOk());
        org.mockito.ArgumentCaptor<java.util.List<?>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(eventPublisher).publishAfterCommit(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("session.started",
                ((io.github.zcd0831.jellyfish.domain.shared.DomainEvent) captor.getValue().get(0)).name());
    }

    @Test
    void 弃用的Provider其下模型不被选中() {
        when(catalog.agents()).thenReturn(Collections.singletonList(agent("builtin.main", AgentStatus.ACTIVE)));
        when(catalog.models()).thenReturn(Collections.singletonList(model("m1", ModelStatus.ACTIVE)));
        when(catalog.tools()).thenReturn(Collections.singletonList(tool(ToolStatus.ACTIVE)));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        // useCase() 里默认塞的是 ACTIVE 的 Provider，这里要后改（Mockito 后设的桩生效）
        StartSessionUseCase useCase = useCase();
        when(catalog.providers()).thenReturn(Collections.singletonList(provider(ProviderStatus.DEPRECATED)));

        // 规则出处：05-action §5.8.2 规则 3（Provider 弃用后其下 Model 不得被选中）
        Result<SessionId> auto = useCase.start(null, null, "你好");
        assertFalse(auto.isOk(), "不应退而选中弃用 Provider 下的模型");
        assertEquals(io.github.zcd0831.jellyfish.domain.perception.session.SessionError.NO_USABLE_MODEL,
                auto.error().error());

        Result<SessionId> explicit = useCase.start(null, ModelId.of("m1"), "你好");
        assertFalse(explicit.isOk(), "显式点名也不行");
    }

    @Test
    void 模型没有对应Provider即不可选() {
        when(catalog.agents()).thenReturn(Collections.singletonList(agent("builtin.main", AgentStatus.ACTIVE)));
        when(catalog.models()).thenReturn(Collections.singletonList(model("m1", ModelStatus.ACTIVE)));
        when(catalog.tools()).thenReturn(Collections.singletonList(tool(ToolStatus.ACTIVE)));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        StartSessionUseCase useCase = useCase();
        when(catalog.providers()).thenReturn(
                Collections.<io.github.zcd0831.jellyfish.domain.action.model.Provider>emptyList());

        assertFalse(useCase.start(null, null, "你好").isOk(), "凭据挂在 Provider 上，没有 Provider 调不通");
    }

    @Test
    void 显式指定的代理不存在即失败() {
        when(catalog.agents()).thenReturn(Collections.singletonList(agent("builtin.main", AgentStatus.ACTIVE)));

        Result<SessionId> result = useCase().start(AgentId.of("nope"), null, "你好");

        assertFalse(result.isOk());
        assertEquals("S_NO_USABLE_AGENT", result.error().error().code());
    }

    @Test
    void 显式指定不可调用的模型即失败() {
        when(catalog.agents()).thenReturn(Collections.singletonList(agent("builtin.main", AgentStatus.ACTIVE)));
        when(catalog.models()).thenReturn(Collections.singletonList(model("m1", ModelStatus.DEPRECATED)));

        Result<SessionId> result = useCase().start(null, null, "你好");

        assertFalse(result.isOk());
        assertEquals("S_NO_USABLE_MODEL", result.error().error().code());
    }

    @Test
    void 子会话继承父的代理与模型且父不存在即失败() {
        when(catalog.agents()).thenReturn(Collections.singletonList(agent("builtin.main", AgentStatus.ACTIVE)));
        when(catalog.models()).thenReturn(Collections.singletonList(model("m1", ModelStatus.ACTIVE)));
        when(catalog.tools()).thenReturn(Collections.<ToolDefinition>emptyList());
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());

        assertFalse(useCase().startSubSession(SessionId.of("missing"), null, null, "任务").isOk());

        Session parent = Session.create(SessionId.of("main"), null, AgentId.of("builtin.main"),
                ModelId.of("m1"), 1L, null);
        when(sessions.load(SessionId.of("main"))).thenReturn(parent);
        when(budgets.load(any(SessionId.class))).thenReturn((Budget) null);

        assertTrue(useCase().startSubSession(SessionId.of("main"), null, null, "任务").isOk());
    }

    /**
     * 内部 fork 也是普通子会话：开启即登记令牌，父已终止则立即终止，子会话不落盘。
     * 规则出处：08-application §8.9.4.1、§8.9.7。
     */
    @Test
    void 父已终止时内部fork子会话不创建() {
        cancellations.register(SessionId.of("main"), null);
        cancellations.cancel(SessionId.of("main"));
        when(sessions.load(SessionId.of("main"))).thenReturn(Session.create(SessionId.of("main"), null,
                AgentId.of("builtin.main"), ModelId.of("m1"), 1L, null));

        assertFalse(useCase().forkInternal(SessionId.of("main")).isOk());
        verify(sessions, never()).save(any(Session.class));
    }

    /** 正常路径：登记后父终止能级联到内部 fork 子会话。规则出处：08-application §8.9.4.1。 */
    @Test
    void 内部fork子会话登记令牌并可被父级联() {
        when(sessions.load(SessionId.of("main"))).thenReturn(Session.create(SessionId.of("main"), null,
                AgentId.of("builtin.main"), ModelId.of("m1"), 1L, null));
        cancellations.register(SessionId.of("main"), null);

        Result<SessionId> forked = useCase().forkInternal(SessionId.of("main"));
        assertTrue(forked.isOk(), "父未终止时照常创建");
        verify(sessions).save(any(Session.class));

        cancellations.cancel(SessionId.of("main"));
        assertTrue(cancellations.forSession(forked.value()).isCancelled(), "父终止要级联到 fork 子会话");
    }
}
