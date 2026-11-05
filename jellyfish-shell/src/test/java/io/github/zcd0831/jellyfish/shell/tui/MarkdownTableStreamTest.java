package io.github.zcd0831.jellyfish.shell.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表格在<b>流式</b>路径上的三种边界：末行没有换行、`|` 开头却不是表、表后紧跟正文。
 * 只断"内容还在"是不够的，顺序也要对——表格被排到正文后面同样是错的。
 * 规则出处：08-application §8.7（不得丢 chunk、不得改序）。
 */
class MarkdownTableStreamTest {

    private static String render(String text) {
        MarkdownStream markdown = new MarkdownStream(false, 80);
        return markdown.feed(text) + markdown.finish();
    }

    @Test
    void 表格末行没有换行也要进表() {
        String out = render("| 名称 | 说明 |\n| --- | --- |\n| a | b |");

        assertTrue(out.contains("─"), "应渲染成表：" + out);
        assertTrue(out.contains("b"), "末行不能被漏掉：" + out);
        assertTrue(!out.trim().endsWith("| a | b |"), "末行不该退回成原始竖线：" + out);
    }

    @Test
    void 竖线开头但不是表时整行保留且顺序不变() {
        String out = render("| 这不是表\n下一行是普通文字\n");

        assertTrue(out.contains("| 这不是表\n"), "这一行不能消失、也不能和下一行粘在一起：" + out);
        assertTrue(out.contains("下一行是普通文字\n"), out);
        assertTrue(out.indexOf("这不是表") < out.indexOf("下一行是普通文字"), "顺序不能反：" + out);
    }

    @Test
    void 表格后面的正文要排在表格之后() {
        String out = render("| 名称 | 说明 |\n| --- | --- |\n| a | b |\n后面这句是正文\n");

        assertTrue(out.contains("─"), "应渲染成表：" + out);
        assertTrue(out.indexOf("后面这句是正文") > out.indexOf("─"), "正文不能被排到表格前面：" + out);
    }

    @Test
    void 逐字符喂进来与整段喂进来结果一致() {
        String text = "| 名称 | 说明 |\n| --- | --- |\n| a | b |\n后面这句是正文\n";

        MarkdownStream chunked = new MarkdownStream(false, 80);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            out.append(chunked.feed(String.valueOf(text.charAt(i))));
        }
        out.append(chunked.finish());

        assertEquals(render(text), out.toString());
    }
}
