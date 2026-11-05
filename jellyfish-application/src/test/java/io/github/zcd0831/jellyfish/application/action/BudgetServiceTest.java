package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.domain.perception.session.SessionHeader;
import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.action.budget.BudgetWarning;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 会话族台账：子会话本地累计，结束后一次性归集。规则出处：05-action §5.8.5。 */
class BudgetServiceTest {

    private static final SessionId ROOT = SessionId.of("root");
    private static final SessionId CHILD = SessionId.of("child");

    private final BudgetRepository budgets = mock(BudgetRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final ProgressPort progress = mock(ProgressPort.class);
    private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    private final Budget budget = Budget.openFor(BudgetId.of("b1"), ROOT);

    private final Session root = Session.create(SessionHeader
            .builder(ROOT, AgentId.of("a"), ModelId.of("m1")).createdDate(1L).build());
    private final Session child = Session.create(SessionHeader
            .builder(CHILD, AgentId.of("a"), ModelId.of("m1")).parentSessionId(ROOT).createdDate(1L).build());

    private BudgetService service() {
        // 全局写段：测试里同步执行，并记下"归集确实进了写段"
        org.mockito.Mockito.doAnswer(new org.mockito.stubbing.Answer<Void>() {
            @Override
            public Void answer(org.mockito.invocation.InvocationOnMock invocation) {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }
        }).when(commitScope).globalWrite(any(Runnable.class));
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        when(budgets.load(ROOT)).thenReturn(budget);
        when(sessions.load(ROOT)).thenReturn(root);
        when(sessions.load(CHILD)).thenReturn(child);
        return new BudgetService(budgets, sessions, policies, commitScope, publisher, progress);
    }

    /**
     * 子代理是在线程池里跑的：直接写族台账会让多个线程的读-改-写相互覆盖。
     * 所以子会话先本地累计，发起线程 join 后一次性归集。
     */
    @Test
    void 子会话用量先本地累计_归集时才写族台账() {
        BudgetService service = service();

        service.recordUsage(child, new ModelUsage(100, 20));
        service.recordUsage(child, new ModelUsage(50, 10));

        assertEquals(0, budget.consumedTokens(), "子会话不得直接写族台账");
        verify(commitScope, never()).stageReplace(any());

        service.settleFromSubSession(CHILD);

        // 并行子代理会同时归集到同一个根台账：读-判-写必须在同一写段里（05-action §5.8.5）
        verify(commitScope).globalWrite(any(Runnable.class));
        assertEquals(180, budget.consumedTokens(), "归集一次性写入全部累计");
        verify(commitScope).stageReplace(budget);
    }

    @Test
    void 没有消耗的子会话归集是空操作() {
        BudgetService service = service();

        service.settleFromSubSession(CHILD);

        assertEquals(0, budget.consumedTokens());
        verify(commitScope, never()).stageReplace(any());
    }

    @Test
    void 族根会话仍然直接记账() {
        BudgetService service = service();

        service.recordUsage(root, new ModelUsage(100, 20));

        assertEquals(120, budget.consumedTokens());
        verify(commitScope).stageReplace(budget);
    }

    /**
     * 归集造成的越阈值只记账 + 发 BudgetWarning，不弹确认：ask_user 只挂在主请求路径上，
     * 归集发生在工具执行中（05-action §5.8.5）。这条用例把“静默过去”钉成已知取舍。
     */
    @Test
    @SuppressWarnings("unchecked")
    void 归集越过阈值只发事件不弹确认() {
        BudgetService service = service();

        service.recordUsage(child, new ModelUsage(900000, 0));
        service.settleFromSubSession(CHILD);

        ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishAfterCommit(events.capture());
        boolean warned = false;
        for (DomainEvent event : events.getValue()) {
            warned = warned || event instanceof BudgetWarning;
        }
        assertTrue(warned, "越阈值要发 BudgetWarning 供界面/审计看见：" + events.getValue());
        assertEquals(900000, budget.consumedTokens());
    }
}
