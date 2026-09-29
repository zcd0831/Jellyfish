package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolName} 的单元测试：前缀、清洗、截断与去重。
 *
 * @author zcd
 */
@DisplayName("MCP 工具名展开")
class McpToolNameTest {

    @Test
    @DisplayName("应展开成 mcp__<server>__<tool>")
    void qualify_should_prefixServerAndTool() {
        assertEquals("mcp__filesystem__read_file",
                McpToolName.qualify("filesystem", "read_file", true, 64));
    }

    @Test
    @DisplayName("关掉前缀时只保留清洗后的工具名")
    void qualify_should_omitPrefix_when_disabled() {
        assertEquals("read_file", McpToolName.qualify("filesystem", "read_file", false, 64));
    }

    @Test
    @DisplayName("厂商不允许的字符应被换成下划线：带点号的名字会让整次请求被拒")
    void qualify_should_sanitizeIllegalCharacters() {
        assertEquals("mcp__srv__a_b_c", McpToolName.qualify("srv", "a.b/c", true, 64));
    }

    @Test
    @DisplayName("超长时应保留前缀并追加哈希，且不超过上限")
    void qualify_should_truncateWithHash() {
        // Given
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longName.append('x');
        }

        // When
        String qualified = McpToolName.qualify("srv", longName.toString(), true, 64);

        // Then
        assertEquals(64, qualified.length());
        assertTrue(qualified.startsWith("mcp__srv__"));
    }

    @Test
    @DisplayName("超长的不同名字截断后仍应互不相同")
    void qualify_should_keepTruncatedNamesDistinct() {
        // Given
        String first = repeat('a', 100) + "one";
        String second = repeat('a', 100) + "two";

        // When
        String firstQualified = McpToolName.qualify("srv", first, true, 32);
        String secondQualified = McpToolName.qualify("srv", second, true, 32);

        // Then：截断若不带哈希，这两个会长得一模一样且后者变成幽灵
        assertNotEquals(firstQualified, secondQualified);
    }

    @Test
    @DisplayName("空名或空白的工具名应退化成 tool 而不是造出空名字")
    void sanitize_should_fallbackForBlank() {
        assertEquals("tool", McpToolName.sanitize(null));
        assertEquals("tool", McpToolName.sanitize("   "));
    }

    @Test
    @DisplayName("isMcpTool 只看前缀")
    void isMcpTool_should_checkPrefix() {
        assertTrue(McpToolName.isMcpTool("mcp__a__b"));
        assertFalse(McpToolName.isMcpTool("read_file"));
        assertFalse(McpToolName.isMcpTool(null));
    }

    /**
     * 生成重复字符。
     *
     * @param character 字符
     * @param count     个数
     * @return 字符串
     */
    private static String repeat(char character, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(character);
        }
        return text.toString();
    }
}
