package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.expectFailure;

/**
 * {@link ToolArguments} 的单元测试。
 * <p>
 * 重点锁住「模型给的参数不可信」这条前提：缺参、类型不对、数字写成字符串，
 * 都必须有确定的行为，且错误文案要能指出是哪个参数。
 *
 * @author zcd
 */
@DisplayName("ToolArguments 参数读取")
class ToolArgumentsTest {

    @Test
    @DisplayName("必需字符串缺失时应报出参数名")
    void requireString_should_fail_when_missing() {
        JellyfishException failure = expectFailure(() -> new ToolArguments(args()).requireString("path"));

        assertEquals("缺少必需参数: path", failure.getMessage());
    }

    @Test
    @DisplayName("必需字符串为空白时应报错")
    void requireString_should_fail_when_blank() {
        JellyfishException failure = expectFailure(
                () -> new ToolArguments(args("path", "   ")).requireString("path"));

        assertEquals("参数 path 不能为空", failure.getMessage());
    }

    @Test
    @DisplayName("必需字符串应去掉首尾空白")
    void requireString_should_trim() {
        assertEquals("a/b.txt", new ToolArguments(args("path", "  a/b.txt  ")).requireString("path"));
    }

    @Test
    @DisplayName("必需文本允许空串，因为空正文是合法输入")
    void requireText_should_acceptBlank() {
        assertEquals("", new ToolArguments(args("content", "")).requireText("content"));
    }

    @Test
    @DisplayName("可选项缺失或为空白时回退默认值")
    void optionalString_should_fallback_when_absentOrBlank() {
        ToolArguments arguments = new ToolArguments(args("path", "   "));

        assertEquals(".", arguments.optionalString("path", "."));
        assertEquals(".", arguments.optionalString("unknown", "."));
    }

    @Test
    @DisplayName("整数可以来自数字或字符串")
    void optionalInt_should_acceptNumberAndNumericString() {
        ToolArguments arguments = new ToolArguments(args("a", 3, "b", "7"));

        assertEquals(3, arguments.optionalInt("a", 0));
        assertEquals(7, arguments.optionalInt("b", 0));
    }

    @Test
    @DisplayName("非整数应报错：小数、非数字字符串都要拦住")
    void optionalInt_should_fail_when_notIntegral() {
        ToolArguments decimal = new ToolArguments(args("a", 1.5));
        ToolArguments text = new ToolArguments(args("a", "abc"));

        assertTrue(expectFailure(() -> decimal.optionalInt("a", 0)).getMessage().contains("必须是整数"));
        assertTrue(expectFailure(() -> text.optionalInt("a", 0)).getMessage().contains("必须是整数"));
    }

    @Test
    @DisplayName("布尔可以来自布尔值或 true/false 字符串")
    void optionalBoolean_should_acceptBooleanAndText() {
        ToolArguments arguments = new ToolArguments(args("a", true, "b", "false"));

        assertTrue(arguments.optionalBoolean("a", false));
        assertFalse(arguments.optionalBoolean("b", true));
    }

    @Test
    @DisplayName("无法识别的布尔应报错")
    void optionalBoolean_should_fail_when_unrecognized() {
        ToolArguments arguments = new ToolArguments(args("a", "yes"));

        assertTrue(expectFailure(() -> arguments.optionalBoolean("a", false)).getMessage().contains("必须是布尔值"));
    }

    @Test
    @DisplayName("空参数等同于没有参数")
    void constructor_should_treatNullAsEmpty() {
        ToolArguments arguments = new ToolArguments(null);

        assertEquals(5, arguments.optionalInt("missing", 5));
    }

    /**
     * 构造参数映射。
     *
     * @param namesAndValues 参数名与值交替出现
     * @return 参数映射
     */
    private static Map<String, Object> args(Object... namesAndValues) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            arguments.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return arguments;
    }
}
