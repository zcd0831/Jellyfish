package io.github.zcd0831.jellyfish.infrastructure.local;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 尾部缓冲必须有界：`yes` / `cat 大日志` / `mvn -X` 的输出量不能换成堆占用。
 * 规则出处：05-action §5.8.1 规则 9（run_command 只回尾部 200 行 + 退出码）。
 */
class OutputTailTest {

    private static void appendText(OutputTail tail, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        tail.append(bytes, bytes.length);
    }

    @Test
    void 超过上限只留最后N行() {
        OutputTail tail = new OutputTail(200);
        for (int i = 1; i <= 100_000; i++) {
            appendText(tail, i + "\n");
        }
        tail.finish();

        assertEquals(200, tail.keptLines(), "内存里只该留 200 行");
        assertEquals(100_000L, tail.totalLines());
        String rendered = tail.render();
        assertTrue(rendered.contains("100000\n"), "最后一行必须在");
        assertTrue(rendered.contains("99801\n"));
        assertFalse(rendered.contains("\n99800\n"), "第 199 条之后的行不该留下");
        assertTrue(rendered.startsWith("（只显示最后 200 行，共 100000 行）"), rendered.substring(0, 40));
    }

    @Test
    void 单行过长只留开头并标记截断() {
        OutputTail tail = new OutputTail(200);
        StringBuilder huge = new StringBuilder();
        while (huge.length() < OutputTail.MAX_LINE_BYTES * 4) {
            huge.append("x");
        }
        appendText(tail, huge + "\n");
        tail.finish();

        String rendered = tail.render();
        assertTrue(rendered.length() < OutputTail.MAX_LINE_BYTES + 200,
                "单行也不能把堆撑开，实际 " + rendered.length());
        assertTrue(rendered.contains("（本行过长，已截断）"));
    }

    @Test
    void 多字节字符跨块不会碎成替换符() {
        OutputTail tail = new OutputTail(200);
        byte[] bytes = "构建成功：全部用例通过\n".getBytes(StandardCharsets.UTF_8);
        // 一个字节一个字节地喂，模拟切分落在汉字中间的极端情况
        for (byte b : bytes) {
            tail.append(new byte[]{b}, 1);
        }
        tail.finish();

        assertEquals("构建成功：全部用例通过\n", tail.render());
    }

    @Test
    void 最后一行没有换行符也要算一行() {
        OutputTail tail = new OutputTail(200);
        appendText(tail, "第一行\n没有换行的最后一行");
        tail.finish();

        assertEquals("第一行\n没有换行的最后一行\n", tail.render());
    }
}
