package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.LineReader;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 输出单写者：所有线程只往队列里投字符串，真正写终端只有这一条线程。
 * <p>
 * 读取进行中必须交给 {@link LineReader#printAbove}，由 JLine 负责擦提示符、打印、再重绘；
 * 否则渲染器会和提示符抢同一行——那正是上一版显示错乱的根因。
 */
public final class OutputGateway {

    private final PrintStream direct;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
    private volatile LineReader reader;
    private volatile boolean closed;

    public OutputGateway(PrintStream direct) {
        this.direct = direct;
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        }, "jellyfish-output");
        writer.setDaemon(true);
        writer.start();
    }

    /** 交给渲染器的落点：写完就入队，永远不会阻塞调用方。 */
    public PrintStream sink() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                offer(new String(new char[]{(char) b}));
            }

            @Override
            public void write(byte[] bytes, int off, int len) {
                offer(new String(bytes, off, len, StandardCharsets.UTF_8));
            }
        }, true);
    }

    /** 输入线程进入/离开 readLine 时告知，决定用 printAbove 还是直写。 */
    public void reading(LineReader reader) {
        this.reader = reader;
    }

    public void idle() {
        this.reader = null;
    }

    public void close() {
        closed = true;
        queue.offer("");
    }

    private void offer(String text) {
        if (!closed && text != null && !text.isEmpty()) {
            queue.offer(text);
        }
    }

    private void pump() {
        List<String> drained = new ArrayList<String>();
        while (!closed) {
            String first;
            try {
                first = queue.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            StringBuilder batch = new StringBuilder(first);
            drained.clear();
            queue.drainTo(drained);
            for (String part : drained) {
                batch.append(part);
            }
            LineReader active = reader;
            if (active != null && active.isReading()) {
                printAbove(active, batch.toString());
            } else {
                direct.print(batch);
                direct.flush();
            }
        }
    }

    /** 读取期只能按整行提交；状态行（只含 \r）是"可丢的"，直接丢弃。 */
    private void printAbove(LineReader active, String batch) {
        for (String line : batch.split("\n")) {
            if (line.isEmpty() || line.indexOf('\r') >= 0) {
                continue;
            }
            try {
                active.printAbove(line);
            } catch (RuntimeException notReading) {
                direct.println(line);
            }
        }
    }
}
