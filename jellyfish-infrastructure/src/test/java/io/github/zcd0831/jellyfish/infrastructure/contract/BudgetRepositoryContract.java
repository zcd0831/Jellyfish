package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `BudgetRepository` 的契约：一族一账、累加、预警只报一次。 */
public abstract class BudgetRepositoryContract {

    protected abstract BudgetRepository repository();

    protected abstract SessionRepository sessions();

    @BeforeEach
    void givenSession() {
        sessions().save(Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null));
    }

    @Test
    void 缺失时返回null_保存后可读回() {
        assertNull(repository().load(SessionId.of("s1")));

        repository().save(Budget.openFor(BudgetId.of("b1"), SessionId.of("s1")));

        assertEquals(0L, repository().load(SessionId.of("s1")).consumedTokens());
    }

    @Test
    void 累加与预警标记被持久化() {
        Budget budget = Budget.openFor(BudgetId.of("b1"), SessionId.of("s1"));
        budget.recordUsage(50L, RuntimePolicy.defaults());
        repository().save(budget);

        assertEquals(50L, repository().load(SessionId.of("s1")).consumedTokens());
    }

    @Test
    void 覆盖保存保留最新累计值() {
        Budget budget = Budget.openFor(BudgetId.of("b1"), SessionId.of("s1"));
        budget.recordUsage(10L, RuntimePolicy.defaults());
        repository().save(budget);
        budget.recordUsage(20L, RuntimePolicy.defaults());
        repository().save(budget);

        assertEquals(30L, repository().load(SessionId.of("s1")).consumedTokens());
        assertTrue(!repository().load(SessionId.of("s1")).warned());
    }
}
