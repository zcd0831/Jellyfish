package io.github.zcd0831.jellyfish.infrastructure.local;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 子进程输出的有界尾部缓冲：只留最后 {@code maxLines} 行，其余随到随丢。
 * <p>
 * 为什么不能先全攒再截断：`yes` / `cat 大日志` / `mvn -X` 这类命令的输出量与堆直接挂钩，
 * 攒满就是一次 OOM——而那些字节从头到尾没人要，规则只允许回尾部
 * （05-action §5.8.1 规则 9：`run_command` 只回尾部 200 行 + 退出码）。
 * <p>
 * 顺带修掉一个隐性缺陷：按字节块切行、按行解码。逐块 {@code new String(byte[])} 会把落在
 * 块边界上的多字节字符劈成两个替换符。
 */
final class OutputTail {

    /** 单行最多留这么多字节：一行 1 GB 时"保留 200 行"照样能把堆占满。 */
    static final int MAX_LINE_BYTES = 8 * 1024;

    private static final String TRUNCATED_LINE_MARK = "…（本行过长，已截断）";

    private final int maxLines;
    private final Deque<String> lines = new ArrayDeque<String>();
    private final ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
    private long totalLines;
    private boolean currentLineTruncated;

    OutputTail(int maxLines) {
        this.maxLines = maxLines;
    }

    /** 只接受原始字节：切行必须在字节层做，否则多字节字符会被块边界劈开。 */
    synchronized void append(byte[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            byte b = buffer[i];
            if (b == '\n') {
                endLine();
            } else if (!currentLineTruncated) {
                if (currentLine.size() >= MAX_LINE_BYTES) {
                    currentLineTruncated = true;
                } else {
                    currentLine.write(b);
                }
            }
        }
    }

    /** 收尾：最后一行没有换行符也要算一行。 */
    synchronized void finish() {
        if (currentLine.size() > 0 || currentLineTruncated) {
            endLine();
        }
    }

    synchronized String render() {
        StringBuilder out = new StringBuilder();
        if (totalLines > maxLines) {
            out.append("（只显示最后 ").append(maxLines).append(" 行，共 ").append(totalLines).append(" 行）\n");
        }
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** 只给断言用：当前真正占着的行数与字节数。 */
    synchronized int keptLines() {
        return lines.size();
    }

    synchronized long totalLines() {
        return totalLines;
    }

    private void endLine() {
        String text = new String(currentLine.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        currentLine.reset();
        if (currentLineTruncated) {
            text = text + TRUNCATED_LINE_MARK;
            currentLineTruncated = false;
        }
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        totalLines++;
        lines.addLast(text);
        while (lines.size() > maxLines) {
            lines.removeFirst();
        }
    }
}
