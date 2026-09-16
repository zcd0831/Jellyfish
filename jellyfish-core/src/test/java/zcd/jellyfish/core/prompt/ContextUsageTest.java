package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextUsage} 的单元测试：比例判定是压缩的触发判据，边界上不能含糊。
 *
 * @author zcd
 */
@DisplayName("上下文用量")
class ContextUsageTest {

    @Test
    @DisplayName("模型没配窗口时比例判定恒为假：分母都没有，不能猜")
    void exceeds_should_beFalse_when_budgetUnknown() {
        ContextUsage usage = ContextUsage.unknown();

        assertFalse(usage.exceeds(1));
        assertEquals(0, usage.getBudgetTokens());
        assertFalse(usage.isTruncated());
    }

    @ParameterizedTest
    @CsvSource({"80, 100, true", "79, 100, false", "100, 100, true", "1, 1, true"})
    @DisplayName("正好到阈值算到（>= 而不是 >）：阈值是「从这里开始压」，不是「超过才压」")
    void exceeds_should_beInclusive(int used, int budget, boolean expected) {
        assertEquals(expected, new ContextUsage(used, budget, false).exceeds(80));
    }

    @Test
    @DisplayName("百分比为 0 或负数时判定为假：那是「关闭自动压缩」的表达方式")
    void exceeds_should_beFalse_when_percentDisabled() {
        ContextUsage usage = new ContextUsage(99, 100, true);

        assertFalse(usage.exceeds(0));
        assertFalse(usage.exceeds(-1));
    }

    @Test
    @DisplayName("已用超过预算时仍然为真：裁剪之后用量可能反过来超过预算")
    void exceeds_should_beTrue_when_usedBeyondBudget() {
        assertTrue(new ContextUsage(200, 100, true).exceeds(80));
    }

    @Test
    @DisplayName("负数用量按 0 处理：估算不会给出负数，出现即说明上游错了，但不该传染")
    void constructor_should_clampNegativeUsed() {
        assertEquals(0, new ContextUsage(-5, 100, false).getUsedTokens());
    }

    @Test
    @DisplayName("已裁剪这个事实与比例无关：它自己要能读出来")
    void isTruncated_should_beIndependentOfRatio() {
        ContextUsage usage = new ContextUsage(1, 100, true);

        assertTrue(usage.isTruncated());
        assertFalse(usage.exceeds(80));
    }
}
