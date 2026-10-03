package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunContext} 的单元测试：验证深度与预算两个独立上限、进出配对与计数。
 * <p>
 * 重点在两个上限必须<b>分别</b>生效：只有深度时一层可以扇出到任意宽度，只有预算时一条链可以无限深。
 *
 * @author zcd
 */
class RunContextTest {

    @Test
    void canDelegate_should_be_true_initially() {
        // When
        RunContext scope = context(2, 4);

        // Then
        assertTrue(scope.canDelegate());
        assertEquals(0, scope.getDepth());
        assertEquals(0, scope.getSpawnCount());
    }

    @Test
    void canDelegate_should_be_false_when_depth_limit_reached() {
        // Given
        RunContext scope = context(1, 8);

        // When
        scope.enter();

        // Then
        assertFalse(scope.canDelegate());
    }

    @Test
    void canDelegate_should_be_false_when_spawn_budget_exhausted() {
        // Given：深度还很宽，但预算用完了
        RunContext scope = context(8, 2);

        // When
        scope.tryAcquireSpawn();
        scope.tryAcquireSpawn();

        // Then
        assertFalse(scope.canDelegate());
        assertEquals(2, scope.getSpawnCount());
    }

    @Test
    void canDelegate_should_be_false_when_max_depth_zero() {
        // When
        RunContext scope = context(0, 8);

        // Then
        assertFalse(scope.canDelegate());
    }

    @Test
    void should_decrement_depth_when_leave() {
        // Given
        RunContext scope = context(2, 8);
        scope.enter();

        // When
        scope.leave();

        // Then
        assertEquals(0, scope.getDepth());
        assertTrue(scope.canDelegate());
    }

    @Test
    void getters_should_return_configured_limits() {
        // When
        RunContext scope = context(3, 7);

        // Then
        assertEquals(3, scope.getMaxDepth());
        assertEquals(7, scope.getMaxSpawnsPerTurn());
    }

    @Test
    void tryAcquireSpawn_should_stop_at_budget() {
        // Given
        RunContext scope = context(8, 2);

        // When / Then
        assertTrue(scope.tryAcquireSpawn());
        assertTrue(scope.tryAcquireSpawn());
        assertFalse(scope.tryAcquireSpawn());
        assertEquals(2, scope.getSpawnCount());
    }

    @Test
    void recordTreeTokens_should_accumulate() {
        // Given
        RunContext scope = context(2, 8);

        // When
        scope.recordTreeTokens(100L);
        long total = scope.recordTreeTokens(50L);

        // Then
        assertEquals(150L, total);
        assertEquals(150L, scope.getTreeTokens());
    }

    @Test
    void budgets_should_be_exposed_from_tree() {
        // When
        RunContext scope = context(2, 8, 100L, 300L);

        // Then
        assertEquals(100L, scope.getRunTokenBudget());
        assertEquals(300L, scope.getTreeTokenBudget());
    }

    /**
     * 借持有者开一个根上下文：构造器是包内可见的，直接 new 会把测试绑在内部结构上。
     *
     * @param maxDepth         允许的最大委派层数
     * @param maxSpawnsPerTurn 允许派生的子代理总数
     * @return 新开的上下文
     */
    private static RunContext context(int maxDepth, int maxSpawnsPerTurn) {
        return context(maxDepth, maxSpawnsPerTurn, 0L, 0L);
    }

    /**
     * 借持有者开一个带 token 预算的根上下文。
     *
     * @param maxDepth         允许的最大委派层数
     * @param maxSpawnsPerTurn 允许派生的子代理总数
     * @param runTokenBudget   单 run token 预算
     * @param treeTokenBudget  树 token 预算
     * @return 新开的上下文
     */
    private static RunContext context(int maxDepth, int maxSpawnsPerTurn, long runTokenBudget,
                                      long treeTokenBudget) {
        RunContextHolder holder = new RunContextHolder();
        holder.open(maxDepth, maxSpawnsPerTurn, runTokenBudget, treeTokenBudget);
        return holder.current();
    }
}
