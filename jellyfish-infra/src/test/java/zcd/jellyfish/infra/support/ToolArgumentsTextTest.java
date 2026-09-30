package zcd.jellyfish.infra.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolArgumentsText} 的单元测试：钉住「同一条参数在所有显示面上是同一份文本」这条前提。
 * <p>
 * 这些断言同时是渲染口径的单一来源——审批浮层、TUI 轨迹行、{@code -cli} 诊断行都走这里，
 * 因此在这里改一条规则，三处会一起变。<b>参数不脱敏</b>这条也在下面被显式钉住，
 * 免得以后有人以为是漏做了（口径与理由见 {@code README.md} 与类注释）。
 *
 * @author zcd
 */
@DisplayName("ToolArgumentsText 参数展示文本")
class ToolArgumentsTextTest {

    @Test
    @DisplayName("参数为空时 text 给出占位符、singleLine 给出空串")
    void empty_arguments_should_differ_betweenTwoForms() {
        // 审批浮层要一行「参数  -」的占位，而轨迹行 / 日志行的空后缀不该多出一个破折号
        assertEquals("-", ToolArgumentsText.text(null));
        assertEquals("-", ToolArgumentsText.text(Collections.<String, Object>emptyMap()));
        assertEquals("", ToolArgumentsText.singleLine(null));
        assertEquals("", ToolArgumentsText.singleLine(Collections.<String, Object>emptyMap()));
    }

    @Test
    @DisplayName("普通参数渲染成紧凑 JSON：字符串加引号、非字符串取字面值")
    void text_should_renderCompactJson() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "a.txt");
        arguments.put("limit", Integer.valueOf(3));
        arguments.put("all", Boolean.TRUE);

        assertEquals("{\"path\": \"a.txt\", \"limit\": 3, \"all\": true}", ToolArgumentsText.text(arguments));
    }

    @Test
    @DisplayName("参数一律原样显示：键名叫 apiKey 也不打码（与 Codex 的 --verbose 同口径）")
    void text_should_notMaskAnything() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("apiKey", "sk-secret");
        arguments.put("access_token", "sk-secret");

        assertEquals("{\"apiKey\": \"sk-secret\", \"access_token\": \"sk-secret\"}",
                ToolArgumentsText.text(arguments));
    }

    @Test
    @DisplayName("控制字符被剔除：参数不能清屏或挪光标")
    void text_should_stripControlCharacters() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("content", "\u001b[2J危险");

        String text = ToolArgumentsText.text(arguments);

        assertFalse(text.contains("\u001b"), text);
        assertTrue(text.contains("危险"), text);
    }

    @Test
    @DisplayName("text 保留换行：审批浮层靠它把一段命令按原样铺开")
    void text_should_keepNewlines() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", "cat <<EOF\nhello\nEOF");

        assertTrue(ToolArgumentsText.text(arguments).contains("\n"),
                ToolArgumentsText.text(arguments));
    }

    @Test
    @DisplayName("singleLine 把空白（含换行）折成一个空格")
    void singleLine_should_collapseWhitespace() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", "cat <<EOF\n\thello\nEOF");

        assertEquals("{\"command\": \"cat <<EOF hello EOF\"}", ToolArgumentsText.singleLine(arguments));
    }

    @Test
    @DisplayName("singleLine 只折空白，不动值里的空格")
    void singleLine_should_notTrimWhitespaceInsideValues() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", "  padded  ");

        assertEquals("{\"command\": \" padded \"}", ToolArgumentsText.singleLine(arguments));
    }

    @Test
    @DisplayName("从 JSON 原文渲染：解析后按同一口径显示")
    void singleLineFromJson_should_parseAndRender() {
        assertEquals("{\"path\": \"a.txt\"}", ToolArgumentsText.singleLineFromJson("{\"path\":\"a.txt\"}"));
        assertEquals("{\"apiKey\": \"sk-1\"}", ToolArgumentsText.singleLineFromJson("{\"apiKey\": \"sk-1\"}"));
    }

    @Test
    @DisplayName("JSON 非法时退回原文，而不是整行消失")
    void singleLineFromJson_should_fallBackToRawText() {
        assertEquals("mvn test --flag", ToolArgumentsText.singleLineFromJson("mvn test --flag"));
        assertEquals("{\"path\": \"a", ToolArgumentsText.singleLineFromJson("{\"path\": \"a"));
    }

    @Test
    @DisplayName("JSON 非法且原文含控制字符时，退回路径同样过滤")
    void singleLineFromJson_should_filterControlCharactersOnFallback() {
        String text = ToolArgumentsText.singleLineFromJson("not json\u001b[2J");

        assertFalse(text.contains("\u001b"), text);
        assertTrue(text.contains("not json"), text);
    }

    @Test
    @DisplayName("空 JSON、空对象与空串都给出空串，调用方因此不必自己判空")
    void singleLineFromJson_should_returnEmptyForNoArguments() {
        assertEquals("", ToolArgumentsText.singleLineFromJson(null));
        assertEquals("", ToolArgumentsText.singleLineFromJson("   "));
        assertEquals("", ToolArgumentsText.singleLineFromJson("{}"));
    }

    @Test
    @DisplayName("collapse 对空值返回空串，不做多余分配")
    void collapse_should_passThroughNullAndEmpty() {
        assertEquals("", ToolArgumentsText.collapse(null));
        assertEquals("", ToolArgumentsText.collapse(""));
        assertEquals("", ToolArgumentsText.collapse("  \n\t "));
    }
}
