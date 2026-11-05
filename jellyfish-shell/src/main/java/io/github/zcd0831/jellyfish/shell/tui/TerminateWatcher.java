package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.IOException;

/**
 * 运行中的按键监听：轮次进行时没人读行，`Esc` 只能靠这里接（08-application §8.9.4.1）。
 * <p>
 * 判别自己做：`Esc` 后 {@link #ESC_WINDOW_MILLIS}ms 内还有字节，就当作 Alt / 转义序列、不终止；
 * 等到超时才是「单按一次 Esc」。这正是 JLine 键位表做不到的事。
 * <p>
 * 用 peek 而不是 read：<strong>不是 Esc 的按键一个都不消费</strong>，
 * 于是运行中打好的字仍然留在终端缓冲里，下一个提示符照常收到（type-ahead 不丢）。
 */
final class TerminateWatcher {

    /** 运行中监听按键的轮询步长：只影响"多久发现一次按键"，不影响终止延迟。 */
    private static final long LISTEN_POLL_MILLIS = 100L;
    /** `Esc` 后等后续字节的窗口：超时即认定「单按 Esc」。 */
    private static final long ESC_WINDOW_MILLIS = 120L;
    /** 转义序列最多还有这么多个字节（方向键 2 个、Alt+Enter 1 个，留足余量）。 */
    private static final int ESC_SEQUENCE_MAX_BYTES = 8;
    private static final int ESC = 0x1B;
    /** JLine 非阻塞读取的 EOF（-2 是"等超时"，不算 EOF）。 */
    private static final int EOF = -1;

    /** 判定为「单按一次 Esc」后要做的事。 */
    interface TerminateSink {
        void onTerminate();
    }

    private final Terminal terminal;
    private final TerminateSink sink;
    private volatile boolean listening;
    private Thread listener;

    TerminateWatcher(Terminal terminal, TerminateSink sink) {
        this.terminal = terminal;
        this.sink = sink;
    }

    /** `Esc` 终止、`Alt+Enter` 换行；绑定失败不影响行尾 `\` 续行。 */
    void bindKeys(LineReader reader) {
        try {
            TerminateKey.bind(reader, new Runnable() {
                @Override
                public void run() {
                    sink.onTerminate();
                }
            });
        } catch (RuntimeException unsupported) {
            // 终端/键位表不支持就算了，行尾 `\` 仍可用
        }
    }

    void start() {
        listening = true;
        listener = new Thread(new Runnable() {
            @Override
            public void run() {
                while (listening) {
                    if (!listenOnce()) {
                        return;
                    }
                }
            }
        }, "jellyfish-terminate-key");
        listener.setDaemon(true);
        listener.start();
    }

    void stop() {
        listening = false;
        Thread current = listener;
        listener = null;
        if (current != null) {
            try {
                current.join(LISTEN_POLL_MILLIS * 4L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 返回 false 表示终端已关闭，监听结束。 */
    private boolean listenOnce() {
        try {
            int peeked = terminal.reader().peek(LISTEN_POLL_MILLIS);
            if (peeked == EOF) {
                return false;
            }
            if (peeked != ESC) {
                return true;   // 不是 Esc：留给下一个提示符
            }
            terminal.reader().read();   // 消费掉这个 Esc，再做窗口判别
            int next = terminal.reader().peek(ESC_WINDOW_MILLIS);
            if (next == EOF) {
                return false;
            }
            if (next < 0) {
                sink.onTerminate();     // 窗口内没有后续字节：这就是「单按一次 Esc」
                return true;
            }
            drainEscapeSequence();      // Alt+X / 方向键：整串吃掉，别让它污染下一次输入
            return true;
        } catch (IOException closed) {
            return false;
        }
    }

    /** 转义序列的尾巴（`[A`、`CR` 之类）可能分几批到：持续吞到静默为止，最多一小段。 */
    private void drainEscapeSequence() {
        for (int i = 0; i < ESC_SEQUENCE_MAX_BYTES; i++) {
            try {
                int more = terminal.reader().read(ESC_WINDOW_MILLIS);
                if (more < 0) {
                    return;
                }
            } catch (IOException closed) {
                return;
            }
        }
    }
}
