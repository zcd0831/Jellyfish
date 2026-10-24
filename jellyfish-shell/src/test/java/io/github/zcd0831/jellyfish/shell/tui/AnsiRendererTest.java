package io.github.zcd0831.jellyfish.shell.tui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 渲染的可测部分：CJK 宽度与折行、状态行用 \r 覆盖。规则出处：D20、D13（不测渲染本身）。 */
class AnsiRendererTest {

    @Test
    void CJK按两列计算() {
        assertEquals(2, AnsiRenderer.charWidth('中'));
        assertEquals(2, AnsiRenderer.charWidth('，'));
        assertEquals(1, AnsiRenderer.charWidth('a'));
    }

    @Test
    void 超过宽度自动折行() throws UnsupportedEncodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AnsiRenderer renderer = new AnsiRenderer(new PrintStream(bytes, true, "UTF-8"), 4);

        renderer.append("中中中");   // 每个 2 列，4 列后折行

        String text = bytes.toString("UTF-8");
        assertEquals(1, text.split("\n", -1).length - 1, "应有 1 次换行：" + text.replace("\n", "\\n"));
    }

    @Test
    void 状态行原地覆盖不清屏() throws UnsupportedEncodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AnsiRenderer renderer = new AnsiRenderer(new PrintStream(bytes, true, "UTF-8"), 80);

        renderer.status("正在重试");
        renderer.status("");

        String text = bytes.toString("UTF-8");
        assertTrue(text.contains("\r\u001B[2K"), "状态行应以 \\r + 清行覆盖");
        assertTrue(!text.contains("\u001B[2J"), "不得整屏清空（D20-2）");
    }

    @Test
    void 状态行前先另起一行不擦正文() throws UnsupportedEncodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AnsiRenderer renderer = new AnsiRenderer(new PrintStream(bytes, true, "UTF-8"), 80);
        renderer.append("答案最后一行");

        renderer.status("思考中…");

        String text = bytes.toString("UTF-8");
        assertTrue(text.contains("答案最后一行\n"), "状态行前应另起一行：" + text);
    }

    @Test
    void 正文续写前先擦状态行() throws UnsupportedEncodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AnsiRenderer renderer = new AnsiRenderer(new PrintStream(bytes, true, "UTF-8"), 80);
        renderer.status("思考中…");

        renderer.append("答案");

        String text = bytes.toString("UTF-8");
        assertTrue(text.contains("\r\u001B[2K"), "正文续写前应擦掉状态行：" + text);
    }

    @Test
    void 提问前先断开当前行() throws UnsupportedEncodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AnsiRenderer renderer = new AnsiRenderer(new PrintStream(bytes, true, "UTF-8"), 80);
        renderer.append("半句话");

        renderer.question("验收", "同意吗？");

        String text = bytes.toString("UTF-8");
        assertTrue(text.contains("[验收] 同意吗？"));
        assertTrue(text.indexOf("半句话") < text.indexOf("[验收]"));
    }
}
