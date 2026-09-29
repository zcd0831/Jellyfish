package zcd.jellyfish.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunScope} 的单元测试：验证深度与预算两个独立上限、进出配对与计数。
 * <p>
 * 重点在两个上限必须<b>分别</b>生效：只有深度时一层可以扇出到任意宽度，只有预算时一条链可以无限深。
 *
 * @author zcd
 */
class RunScopeTest {

    @Test
    void canDelegate_should_be_true_initially() {
        // When
        RunScope scope = new RunScope(2, 4);

        // Then
        assertTrue(scope.canDelegate());
        assertEquals(0, scope.getDepth());
        assertEquals(0, scope.getSpawnCount());
    }

    @Test
    void canDelegate_should_be_false_when_depth_limit_reached() {
        // Given
        RunScope scope = new RunScope(1, 8);

        // When
        scope.enter();

        // Then
        assertFalse(scope.canDelegate());
    }

    @Test
    void canDelegate_should_be_false_when_spawn_budget_exhausted() {
        // Given：深度还很宽，但预算用完了
        RunScope scope = new RunScope(8, 2);

        // When
        scope.recordSpawn();
        scope.recordSpawn();

        // Then
        assertFalse(scope.canDelegate());
        assertEquals(2, scope.getSpawnCount());
    }

    @Test
    void canDelegate_should_be_false_when_max_depth_zero() {
        // When
        RunScope scope = new RunScope(0, 8);

        // Then
        assertFalse(scope.canDelegate());
    }

    @Test
    void should_decrement_depth_when_leave() {
        // Given
        RunScope scope = new RunScope(2, 8);
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
        RunScope scope = new RunScope(3, 7);

        // Then
        assertEquals(3, scope.getMaxDepth());
        assertEquals(7, scope.getMaxSpawnsPerTurn());
    }
}
