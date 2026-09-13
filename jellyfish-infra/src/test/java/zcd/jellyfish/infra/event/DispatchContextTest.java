package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.callback.CallbackException;
import zcd.jellyfish.api.event.callback.PluginRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DispatchContext} 的单元测试：验证在途命令栈、嵌套深度限制与退出清理。
 *
 * @author zcd
 */
class DispatchContextTest {

    /** 指标。 */
    private final EventBusStats stats = new EventBusStats();

    @Test
    void current_should_return_null_and_exit_should_be_safe_when_no_command() {
        // Given
        DispatchContext context = new DispatchContext(2, stats);

        // When
        context.exit();

        // Then
        assertNull(context.current());
    }

    @Test
    void enter_and_exit_should_track_current_command() {
        // Given
        DispatchContext context = new DispatchContext(2, stats);
        PluginRequest callback = callback("calc");

        // When
        context.enter(callback);

        // Then
        assertSame(callback, context.current());

        // When
        context.exit();

        // Then
        assertNull(context.current());
    }

    @Test
    void exit_should_restore_outer_command_when_nested() {
        // Given
        DispatchContext context = new DispatchContext(2, stats);
        PluginRequest outer = callback("outer");
        PluginRequest inner = callback("inner");
        context.enter(outer);
        context.enter(inner);

        // When
        context.exit();

        // Then
        assertSame(outer, context.current());
    }

    @Test
    void enter_should_reject_when_depth_exceeded() {
        // Given
        DispatchContext context = new DispatchContext(1, stats);
        PluginRequest outer = callback("outer");
        context.enter(outer);

        // When
        CallbackException exception = assertThrows(CallbackException.class, () -> context.enter(callback("inner")));

        // Then
        assertEquals(CallbackException.Code.NESTING_TOO_DEEP, exception.getCode());
        assertEquals(1L, stats.getNestingRejectedCallbacks());
        assertSame(outer, context.current());
    }

    @Test
    void stats_should_return_injected_stats() {
        // Given
        DispatchContext context = new DispatchContext(2, stats);

        // Then
        assertSame(stats, context.stats());
    }

    /**
     * 构造插件命令。
     *
     * @param name 命令名
     * @return 插件命令
     */
    private static PluginRequest callback(String name) {
        return new PluginRequest(name, Object.class, null);
    }
}
