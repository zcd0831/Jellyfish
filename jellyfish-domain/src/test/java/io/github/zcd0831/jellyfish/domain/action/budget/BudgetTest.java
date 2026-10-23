package io.github.zcd0831.jellyfish.domain.action.budget;

import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 只累加，不预留、不中断。规则出处：模块-行动 §7.5 规则 ②③。
 */
class BudgetTest {

    @Test
    void 每次调用后累加() {
        Budget budget = Budget.openFor(BudgetId.of("b1"), SessionId.of("s1"));

        budget.recordUsage(120L);
        budget.recordUsage(80L);

        assertEquals(200L, budget.consumedTokens());
        assertEquals(SessionId.of("s1"), budget.sessionFamilyRootId());
    }
}
