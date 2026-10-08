package zcd.jellyfish.server.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogText} 的单元测试：把请求原文压成单行日志文本的四条规则。
 *
 * @author zcd
 */
@DisplayName("LogText 日志文本准备")
class LogTextTest {

    @Test
    @DisplayName("控制字符应被剥掉：ESC 能在终端里清屏、改标题")
    void singleLine_should_stripControlChars() {
        String text = LogText.singleLine("会话\u001b[2J不存在");

        assertFalse(text.contains("\u001b"), text);
        assertEquals("会话[2J不存在", text);
    }

    @Test
    @DisplayName("换行应换成空格：它能在日志里伪造出一整行")
    void singleLine_should_replaceNewline() {
        assertEquals("a b", LogText.singleLine("a\nb"));
        // `\r` 是控制字符，由 ControlChars 直接剥掉；`\n` 在这里换成空格
        assertEquals("a b c", LogText.singleLine("a\r\nb\nc"));
    }

    @Test
    @DisplayName("过长文本应截断：几 MB 的输入会把日志上下文挤走")
    void singleLine_should_truncateLongText() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longText.append('x');
        }

        String text = LogText.singleLine(longText.toString());

        assertEquals(201, text.length());
        assertTrue(text.endsWith("…"), text);
    }

    @Test
    @DisplayName("null 应返回空串：调用点不必为「没有原文」再写一个分支")
    void singleLine_should_returnEmpty_when_inputNull() {
        assertEquals("", LogText.singleLine(null));
    }
}
