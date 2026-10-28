package io.github.zcd0831.jellyfish.domain.action.budget;

import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只累加，不预留、不中断；越过 budgetLimitTokens × budgetWarnRatio 只报一次。
 * 规则出处：模块-行动 §7.5 规则 ②③④、§8.3。
 */
class BudgetTest {

    private static RuntimePolicy policyWithLimit(int limit) {
        RuntimePolicy d = RuntimePolicy.defaults();
        return new RuntimePolicy(d.maxRetries(), d.reflectionThreshold(), d.maxReplanGeneration(),
                d.maxReActRounds(), d.summarizationTokenThreshold(), d.inputSafetyMargin(),
                d.collapseRetainRecordCount(), d.toolResultTruncateLength(),
                d.subagentExecutionTimeoutMillis(), d.experienceCapacity(), d.experienceTopN(),
                d.experiencePerPlan(), d.experienceInitialConfidence(), d.experienceConfidenceStep(),
                d.experienceRetireThreshold(), d.skillCapacity(), d.budgetWarnRatio(), limit,
                d.modelFallbackChain(), d.trustedToolIds());
    }

    @Test
    void 每次调用后累加() {
        Budget budget = Budget.openFor(BudgetId.of("b1"), SessionId.of("s1"));

        budget.recordUsage(120L, policyWithLimit(1000));
        budget.recordUsage(80L, policyWithLimit(1000));

        assertEquals(200L, budget.consumedTokens());
        assertEquals(SessionId.of("s1"), budget.sessionFamilyRootId());
    }

    @Test
    void 首次越过阈值才预警且只报一次() {
        RuntimePolicy policy = policyWithLimit(100);
        Budget budget = Budget.openFor(BudgetId.of("b1"), SessionId.of("s1"));

        budget.recordUsage(50L, policy);
        assertFalse(budget.warned());
        assertTrue(budget.pullEvents().isEmpty());

        budget.recordUsage(40L, policy);
        assertTrue(budget.warned());
        assertEquals(1, budget.pullEvents().size());

        budget.recordUsage(1000L, policy);
        assertTrue(budget.pullEvents().isEmpty());
    }

    @Test
    void 额度为零时不预警() {
        RuntimePolicy policy = policyWithLimit(0);
        Budget budget = Budget.openFor(BudgetId.of("b1"), SessionId.of("s1"));

        budget.recordUsage(100000L, policy);

        assertFalse(budget.warned());
    }

    @Test
    void 记下最近一次请求的用量并在折叠后清掉() {
        Budget budget = Budget.openFor(BudgetId.of("b1"), SessionId.of("s1"));

        budget.observePrompt(SessionId.of("s2"), 18200L, false);

        assertEquals(18200L, budget.lastPromptTokens());
        assertFalse(budget.lastPromptEstimated(), "厂商给的是精确值");
        assertEquals("s2", budget.lastPromptSessionId());

        budget.observePrompt(SessionId.of("s2"), 300L, true);
        assertTrue(budget.lastPromptEstimated(), "厂商没给时是估算，界面要加 ≈");

        budget.forgetPromptObservation();
        assertEquals(0L, budget.lastPromptTokens());
        assertEquals(null, budget.lastPromptSessionId());
    }
}
