package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.perception.session.AgentSnapshotInput;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
import io.github.zcd0831.jellyfish.domain.perception.session.SkillSummarySnapshot;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptSnapshot;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
/**
 * 会话的唯一创建入口：解析 agent/model → 冻结快照 → 建台账 → 生成标题 → 一次提交。
 * 规则出处：04-perception §4.5、§4.9.1；08-application §8.9.2。
 */
public final class StartSessionUseCase {

    private static final AgentId DEFAULT_AGENT = AgentId.of("builtin.main");

    private final CatalogRepository catalog;
    private final SessionRepository sessions;
    private final BudgetRepository budgets;
    private final SessionTitleGenerator titleGenerator;
    private final SystemPromptAssembler assembler;
    private final CommitScope commitScope;
    private final Clock clock;
    private final SessionCancellations cancellations;
    private final DomainEventPublisher eventPublisher;

    public StartSessionUseCase(CatalogRepository catalog, SessionRepository sessions, BudgetRepository budgets,
                               SessionTitleGenerator titleGenerator, SystemPromptAssembler assembler,
                               CommitScope commitScope, Clock clock, SessionCancellations cancellations,
                               DomainEventPublisher eventPublisher) {
        this.catalog = catalog;
        this.sessions = sessions;
        this.budgets = budgets;
        this.titleGenerator = titleGenerator;
        this.assembler = assembler;
        this.commitScope = commitScope;
        this.clock = clock;
        this.cancellations = cancellations;
        this.eventPublisher = eventPublisher;
    }

    public Result<SessionId> start(AgentId requestedAgent, ModelId requestedModel, String firstInput) {
        AgentDefinition agent = resolveAgent(requestedAgent);
        if (agent == null) {
            return Result.fail(UseCaseError.of(SessionError.NO_USABLE_AGENT, "没有可用的代理", requestedAgent));
        }
        Model model = resolveModel(requestedModel);
        if (model == null) {
            return Result.fail(UseCaseError.of(SessionError.NO_USABLE_MODEL, "没有可用的模型", requestedModel));
        }
        SessionId sessionId = SessionId.of(UUID.randomUUID().toString());
        Session session = Session.create(sessionId, null, agent.id(), model.id(), clock.nowMillis(),
                freeze(agent));
        Record userRecord = session.appendRecord(RecordRole.USER, firstInput, null, clock.nowMillis());
        Budget budget = Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), sessionId);
        session.retitle(titleGenerator.generate(session, firstInput));

        commitScope.stageAppend(sessionId, userRecord);
        commitScope.stageReplace(session);
        commitScope.stageReplace(budget);
        commitScope.commit();
        publishStarted(session);
        return Result.ok(sessionId);
    }

    /**
     * 子代理派遣：同一套快照冻结，但**不自建 Budget**（沿 parentSessionId 上溯到根）。
     * 规则出处：04-perception §4.9.1、05-action §5.8.5 规则 5。
     */
    public Result<SessionId> startSubSession(SessionId parentSessionId, AgentId requestedAgent,
                                             ModelId requestedModel, String task) {
        return startSubSession(parentSessionId, requestedAgent, requestedModel, task, null);
    }

    /**
     * 带预先建好的终止信号：子代理的令牌必须先于会话存在（超时可能落在会话创建阶段）。
     * 规则出处：08-application §8.9.4.1。
     */
    public Result<SessionId> startSubSession(SessionId parentSessionId, AgentId requestedAgent,
                                             ModelId requestedModel, String task,
                                             CancellationToken signal) {
        Session parent = sessions.load(parentSessionId);
        if (parent == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "父会话不存在", parentSessionId));
        }
        AgentDefinition agent = resolveAgent(requestedAgent == null ? parent.agentId() : requestedAgent);
        if (agent == null) {
            return Result.fail(UseCaseError.of(SessionError.NO_USABLE_AGENT, "没有可用的代理", requestedAgent));
        }
        Model model = resolveModel(requestedModel == null ? parent.activeModelId() : requestedModel);
        if (model == null) {
            return Result.fail(UseCaseError.of(SessionError.NO_USABLE_MODEL, "没有可用的模型", requestedModel));
        }
        SessionId childId = SessionId.of(UUID.randomUUID().toString());
        Session child = Session.create(childId, parentSessionId, agent.id(), model.id(), clock.nowMillis(),
                freeze(agent));
        // 开启即登记终止令牌：起标题、写流水这些“运行前”的动作也必须可被终止（08-application §8.9.4.1）
        if (signal == null) {
            cancellations.registerIfAbsent(childId, parentSessionId);
        } else {
            cancellations.attach(childId, parentSessionId, signal);
        }
        Record userRecord = child.appendRecord(RecordRole.USER, task, null, clock.nowMillis());
        child.retitle(titleGenerator.generate(child, task));
        // 起标题期间可能已经超时：会话还没落盘就终止，宁可不建，也不留一个“建到一半”的子会话
        // （终止后不得再产生副作用，08-application §8.9.4.1）
        if (signal != null && signal.isCancelled()) {
            cancellations.unregister(childId);
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "子会话被终止，未创建", childId));
        }

        // 先建出 sub/<child>/ 目录：追加流水的路径只能由会话头推导
        sessions.save(child);
        commitScope.stageAppend(childId, userRecord);
        commitScope.stageReplace(child);
        commitScope.commit();
        publishStarted(child);
        return Result.ok(childId);
    }

    /**
     * 内部子会话（fork 型调用）：沿用父会话的冻结快照与模型，**不生成标题、不自建 Budget**；
     * 它落在 <code>sub/</code> 下，因此天然不进会话列表（08-application §8.9.7）。
     */
    public Result<SessionId> forkInternal(SessionId parentSessionId) {
        Session parent = sessions.load(parentSessionId);
        if (parent == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "父会话不存在", parentSessionId));
        }
        SessionId childId = SessionId.of(UUID.randomUUID().toString());
        Session child = Session.create(childId, parentSessionId, parent.agentId(), parent.activeModelId(),
                clock.nowMillis(), parent.systemPromptSnapshot());
        child.retitle(parent.title());
        sessions.save(child);
        commitScope.stageReplace(child);
        commitScope.commit();
        // 内部 fork 型子会话不发 SessionStarted：它不进会话列表（04-perception §4.7、08-application §8.9.7）
        child.pullEvents();
        return Result.ok(childId);
    }

    /** 提交后派发创建事件：只供界面 / 审计，不驱动任何动作（04-perception §4.8.1 规则 1）。 */
    private void publishStarted(Session session) {
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(session.pullEvents()));
    }

    /** 快照冻结：只收 ACTIVE 且被该代理绑定的工具与技能（§7.1 规则 4、§8.3）。 */
    private SystemPromptSnapshot freeze(AgentDefinition agent) {
        List<ToolSchemaSnapshot> tools = new ArrayList<ToolSchemaSnapshot>();
        for (ToolDefinition tool : catalog.tools()) {
            if (tool.isActive() && agent.listBoundToolIds().contains(tool.id())) {
                tools.add(new ToolSchemaSnapshot(tool.name(), tool.description(),
                        tool.inputSchema().getJsonSchemaString(),
                        tool.outputSchema() == null ? null : tool.outputSchema().getJsonSchemaString()));
            }
        }
        List<SkillSummarySnapshot> skills = new ArrayList<SkillSummarySnapshot>();
        for (SkillDefinition skill : catalog.skills()) {
            if (skill.isActive() && agent.listBoundSkillIds().contains(skill.id())) {
                skills.add(new SkillSummarySnapshot(skill.name(), skill.description()));
            }
        }
        return assembler.assemble(new AgentSnapshotInput(agent.id(), agent.basePrompt(), null, tools, skills,
                new ArrayList<String>()));
    }

    private AgentDefinition resolveAgent(AgentId requested) {
        AgentDefinition fallback = null;
        for (AgentDefinition agent : catalog.agents()) {
            if (!agent.isActive()) {
                continue;
            }
            if (requested != null && agent.id().equals(requested)) {
                return agent;
            }
            if (agent.id().equals(DEFAULT_AGENT)) {
                fallback = agent;
            }
        }
        return requested == null ? fallback : null;
    }

    private Model resolveModel(ModelId requested) {
        Model fallback = null;
        for (Model model : catalog.models()) {
            if (!model.isCallable()) {
                continue;
            }
            if (requested != null && model.id().equals(requested)) {
                return model;
            }
            if (fallback == null) {
                fallback = model;
            }
        }
        return requested == null ? fallback : null;
    }

    /** 供 S3 的子会话创建复用：同一套快照冻结与台账规则。 */
    public SessionRepository sessions() {
        return sessions;
    }

    public BudgetRepository budgets() {
        return budgets;
    }

    public List<ToolId> boundToolIds(AgentId agentId) {
        for (AgentDefinition agent : catalog.agents()) {
            if (agent.id().equals(agentId)) {
                return agent.listBoundToolIds();
            }
        }
        return new ArrayList<ToolId>();
    }
}
