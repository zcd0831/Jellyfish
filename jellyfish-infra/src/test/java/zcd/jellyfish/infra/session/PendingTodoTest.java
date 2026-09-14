package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingTodo} 的单元测试：验证工厂、状态流转与构造期校验。
 *
 * @author zcd
 */
class PendingTodoTest {

    @Test
    void pending_should_create_pending_todo_with_fields() {
        // When
        PendingTodo todo = PendingTodo.pending("1", "写单测", 100L);

        // Then
        assertEquals("1", todo.getId());
        assertEquals("写单测", todo.getContent());
        assertEquals(100L, todo.getCreatedAt());
        assertEquals(PendingTodo.Status.PENDING, todo.getStatus());
        assertTrue(todo.isPending());
        assertFalse(todo.isDone());
    }

    @Test
    void done_should_return_new_done_todo_preserving_fields() {
        // Given
        PendingTodo todo = PendingTodo.pending("1", "写单测", 100L);

        // When
        PendingTodo done = todo.done();

        // Then
        assertTrue(done.isDone());
        assertEquals("1", done.getId());
        assertEquals("写单测", done.getContent());
        assertEquals(100L, done.getCreatedAt());
    }

    @Test
    void done_should_return_self_when_already_done() {
        // Given
        PendingTodo done = PendingTodo.pending("1", "写单测", 100L).done();

        // When
        PendingTodo again = done.done();

        // Then
        assertSame(done, again);
    }

    @Test
    void constructor_should_reject_blank_fields() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> new PendingTodo("  ", "content", PendingTodo.Status.PENDING, 0L));
        assertThrows(JellyfishException.class,
                () -> new PendingTodo("1", "  ", PendingTodo.Status.PENDING, 0L));
        assertThrows(JellyfishException.class, () -> new PendingTodo("1", "content", null, 0L));
    }
}
