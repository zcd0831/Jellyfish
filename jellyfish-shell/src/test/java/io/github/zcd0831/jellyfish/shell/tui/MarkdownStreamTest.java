package io.github.zcd0831.jellyfish.shell.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 行内 Markdown：只认常用几种，未闭合的标记按字面输出。 */
class MarkdownStreamTest {

    private static String plain(String text) {
        MarkdownStream markdown = new MarkdownStream(false);
        return markdown.feed(text) + markdown.finish();
    }

    @Test
    void 粗体与斜体去掉标记() {
        assertEquals("你好世界", plain("**你好**世界"));
        assertEquals("斜体", plain("*斜体*"));
    }

    @Test
    void 行内代码去掉反引号() {
        assertEquals("跑 ls 命令", plain("跑 `ls` 命令"));
    }

    @Test
    void 标题与列表与引用按行首识别() {
        assertEquals("标题\n", plain("# 标题\n"));
        assertEquals("• 一\n• 二\n", plain("- 一\n* 二\n"));
        assertEquals("│ 引用\n", plain("> 引用\n"));
    }

    @Test
    void 围栏整行吞掉且围栏内原样保留() {
        MarkdownStream markdown = new MarkdownStream(false);
        String out = markdown.feed("```java\nint a = 1; // **原样**\n```\n尾巴");

        assertEquals("int a = 1; // **原样**\n尾巴", out);
        assertFalse(markdown.inFence(), "闭合后应退出围栏");
    }

    @Test
    void 围栏未闭合时保持原样模式() {
        MarkdownStream markdown = new MarkdownStream(false);
        markdown.feed("```\n代码 **粗**");

        assertTrue(markdown.inFence());
    }

    @Test
    void 未闭合的强调渲染到行尾不吐标记() {
        assertEquals("半截", plain("**半截"), "开了粗体就渲染到行尾，不留星号");
        assertEquals("2 * 3", plain("2 * 3"), "空格夹住的星号是字面量");
    }

    @Test
    void 开颜色时插入ANSI且关颜色时没有() {
        String colored = new MarkdownStream(true).feed("**粗**");
        String plain = new MarkdownStream(false).feed("**粗**");

        assertTrue(colored.contains("\u001B[1m"), colored);
        assertFalse(plain.contains("\u001B"), plain);
        assertEquals("粗", plain);
    }

    @Test
    void 收尾时把残留标记吐出并复位围栏() {
        MarkdownStream markdown = new MarkdownStream(false);
        markdown.feed("```\n代码");
        String tail = markdown.finish();

        assertTrue(tail.isEmpty(), "围栏内不产生多余字符：" + tail);
        assertFalse(markdown.inFence(), "收尾要复位围栏，下一轮才不会继续原样");
    }

    @Test
    void 链接渲染成文字加括号里的地址() {
        assertEquals("看文档（https://x.y/z）", plain("看[文档](https://x.y/z)"));
    }

    @Test
    void 链接跨分块也能凑齐() {
        MarkdownStream markdown = new MarkdownStream(false);
        String out = markdown.feed("看[文") + markdown.feed("档](https://x.y") + markdown.feed("/z)");

        assertEquals("看文档（https://x.y/z）", out);
    }

    @Test
    void 不是链接的方括号原样输出() {
        assertEquals("[1] 条目", plain("[1] 条目"));
    }
}
