package zcd.jellyfish.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RunScopes} 的单元测试：验证开闭、覆盖与线程封闭。
 * <p>
 * 线程封闭是最要紧的一条：作用域刻意不是全局状态，否则并发的两个回合会互相吃掉对方的预算。
 *
 * @author zcd
 */
class RunScopesTest {

    @Test
    void current_should_return_null_before_open() {
        // When / Then
        assertNull(new RunScopes().current());
    }

    @Test
    void open_should_expose_scope_with_given_limits() {
        // Given
        RunScopes scopes = new RunScopes();

        // When
        scopes.open(2, 32);

        // Then
        RunScope scope = scopes.current();
        assertNotNull(scope);
        assertEquals(2, scope.getMaxDepth());
        assertEquals(32, scope.getMaxSpawnsPerTurn());
    }

    @Test
    void close_should_clear_scope() {
        // Given
        RunScopes scopes = new RunScopes();
        scopes.open(2, 32);

        // When
        scopes.close();

        // Then：react 池线程会被复用，不清掉就等于下个回合继承本次的计数
        assertNull(scopes.current());
    }

    @Test
    void open_should_replace_previous_scope() {
        // Given
        RunScopes scopes = new RunScopes();
        scopes.open(2, 32);
        RunScope first = scopes.current();
        first.recordSpawn();

        // When
        scopes.open(2, 32);

        // Then：旧作用域被整体换掉，计数不跨回合累积
        RunScope second = scopes.current();
        assertNotSame(first, second);
        assertEquals(0, second.getSpawnCount());
    }

    @Test
    void scope_should_be_isolated_per_thread() throws InterruptedException {
        // Given：主线程开了一个作用域
        RunScopes scopes = new RunScopes();
        scopes.open(2, 32);
        RunScope[] otherThreadScope = new RunScope[1];

        // When
        Thread thread = new Thread(() -> otherThreadScope[0] = scopes.current(), "scope-probe");
        thread.start();
        thread.join();

        // Then：另一个线程看不到它——并发回合互不影响
        assertNull(otherThreadScope[0]);
        assertNotNull(scopes.current());
    }
}
