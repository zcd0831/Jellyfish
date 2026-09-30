package zcd.jellyfish.infra.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CancellationTokenSource} 的单元测试：验证「回调恰好一次」与取消的幂等。
 * <p>
 * 这套语义原先散落在 {@code ReActTurnImpl} 里，抽出来后必须逐条钉住——它是输入指令
 * （{@code Esc} 取消命令行）能生效的前提。
 *
 * @author zcd
 */
class CancellationTokenSourceTest {

    @Test
    void isCancelled_should_be_false_before_cancel() {
        // Given
        CancellationTokenSource source = new CancellationTokenSource();

        // Then
        assertFalse(source.isCancelled());
    }

    @Test
    void cancel_should_be_idempotent_and_run_callback_once() {
        // Given
        CancellationTokenSource source = new CancellationTokenSource();
        AtomicInteger calls = new AtomicInteger();
        source.onCancel(calls::incrementAndGet);

        // When
        source.cancel();
        source.cancel();

        // Then
        assertTrue(source.isCancelled());
        assertEquals(1, calls.get());
    }

    @Test
    void onCancel_should_run_immediately_when_already_cancelled() {
        // Given
        CancellationTokenSource source = new CancellationTokenSource();
        source.cancel();
        AtomicInteger calls = new AtomicInteger();

        // When
        source.onCancel(calls::incrementAndGet);

        // Then
        assertEquals(1, calls.get());
    }

    @Test
    void onCancel_should_ignore_null_callback() {
        // Given
        CancellationTokenSource source = new CancellationTokenSource();

        // When / Then：不应抛异常
        source.onCancel(null);
        source.cancel();
        assertTrue(source.isCancelled());
    }

    @Test
    void cancel_should_run_remaining_callbacks_when_one_throws() {
        // Given
        CancellationTokenSource source = new CancellationTokenSource();
        AtomicInteger reached = new AtomicInteger();
        source.onCancel(() -> {
            throw new IllegalStateException("坏回调");
        });
        source.onCancel(reached::incrementAndGet);

        // When：一个回调抛错不得拦住其余回调，也不得让 cancel 上抛
        source.cancel();

        // Then
        assertEquals(1, reached.get());
    }
}
