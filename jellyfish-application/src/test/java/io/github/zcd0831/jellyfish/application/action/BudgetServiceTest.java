package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private final Budget budget = Budget.openFor(BudgetId.of("b1"), ROOT);

    private final Session root = Session.create(ROOT, null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
    private final Session child = Session.create(CHILD, ROOT, AgentId.of("a"), ModelId.of("m1"), 1L, null);

    private BudgetService service() {
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        when(budgets.load(ROOT)).thenReturn(budget);
        when(sessions.load(ROOT)).thenReturn(root);
        when(sessions.load(CHILD)).thenReturn(child);
        return new BudgetService(budgets, sessions, policies, commitScope, events -> { }, progress);
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
}
