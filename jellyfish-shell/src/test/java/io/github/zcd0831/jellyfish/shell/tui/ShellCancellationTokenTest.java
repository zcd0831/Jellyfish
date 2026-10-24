package io.github.zcd0831.jellyfish.shell.tui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 令牌语义：取消是终态、回调立即生效、可复位（§8.4.1）。 */
class ShellCancellationTokenTest {

    @Test
    void 取消后抛中断且回调被执行() {
        ShellCancellationToken token = new ShellCancellationToken();
        AtomicInteger callbacks = new AtomicInteger();
        token.onCancel(callbacks::incrementAndGet);

        assertFalse(token.isCancelled());
        token.cancel();

        assertTrue(token.isCancelled());
        assertEquals(1, callbacks.get());
        assertThrows(CancellationException.class, token::throwIfCancelled);
    }

    @Test
    void 复位后回到未取消() {
        ShellCancellationToken token = new ShellCancellationToken();
        token.cancel();

        token.reset();

        assertFalse(token.isCancelled());
    }

    @Test
    void 取消后注册的回调立刻执行() {
        ShellCancellationToken token = new ShellCancellationToken();
        token.cancel();
        AtomicInteger callbacks = new AtomicInteger();

        token.onCancel(callbacks::incrementAndGet);

        assertEquals(1, callbacks.get());
    }
}
