package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `Esc` = 一次终止：按键真的被消费、读取不卡住（不是把一个 ESC 前缀留在缓冲区里）。
 * 规则出处：08-application §8.9.4.1（用户终止 = 按一次 Esc）。
 */
class TerminateKeyTest {

    TerminateKeyTest() throws java.io.IOException {
        fromWriter = new PipedInputStream(toReader);
    }

    private final PipedOutputStream toReader = new PipedOutputStream();
    private final PipedInputStream fromWriter;

    @Test
    void 单个Esc触发终止() throws Exception {
        AtomicInteger terminated = new AtomicInteger();
        Outcome outcome = read("\u001B" + "exit\n", new AtomicInteger());

        assertTrue(outcome.line.contains("exit"), outcome.line);
        assertEquals(1, outcome.terminated, "Esc 必须被消费成一次终止");
    }

    private Outcome read(String script, final AtomicInteger terminated) throws Exception {
        // 逐字节送进去（真实终端也不会把 ESC 与后续字节粘成一次读）
        for (byte b : script.getBytes(StandardCharsets.UTF_8)) {
            toReader.write(b);
            toReader.flush();
            Thread.sleep(2);
        }
        toReader.close();
        final Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().dumb(true)
                    .streams(fromWriter, new ByteArrayOutputStream()).build();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        final LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        TerminateKey.bind(reader, new Runnable() {
            @Override
            public void run() {
                terminated.incrementAndGet();
            }
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> read = executor.submit(new Callable<String>() {
                @Override
                public String call() {
                    try {
                        return reader.readLine("> ");
                    } catch (UserInterruptException | EndOfFileException stop) {
                        return "<" + stop.getClass().getSimpleName() + ">";
                    }
                }
            });
            try {
                return new Outcome(read.get(5, TimeUnit.SECONDS), terminated.get());
            } catch (java.util.concurrent.TimeoutException e) {
                read.cancel(true);
                throw new AssertionError("按键没有被消费，读取卡住了（Esc 绑定可能与前缀冲突）");
            }
        } finally {
            executor.shutdownNow();
            terminal.close();
        }
    }

    private static final class Outcome {

        private final String line;
        private final int terminated;

        Outcome(String line, int terminated) {
            this.line = line;
            this.terminated = terminated;
        }
    }
}
