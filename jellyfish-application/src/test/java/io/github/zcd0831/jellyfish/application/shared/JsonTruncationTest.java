package io.github.zcd0831.jellyfish.application.shared;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 模型输出被 max_tokens 截断时，解析器只能抛 IllegalArgumentException——
 * 漏出 StringIndexOutOfBoundsException 会让失败语义消失（上层接不住），
 * 变成"某个没人预期的运行时异常"。
 * 规则出处：05-action §5.9.2、08-application §8.10（工具参数解析失败 ⇒ 工具错误结果）。
 */
class JsonTruncationTest {

    private static final List<String> TRUNCATED = Arrays.asList(
            "{\"a\":",                    // 值缺失
            "{\"a\":1",                   // 对象未闭合
            "[1,2",                       // 数组未闭合
            "{\"a\":\"未闭合",             // 字符串未闭合
            "{\"a\":\"" + "\\" + "u12",   // 四位十六进制转义只剩两位
            "{\"a\":\"" + "\\",           // 反斜杠后没了
            "{\"a\":-",                   // 数字只剩符号
            "{\"a\":1e",                  // 科学计数法截断
            "-");                         // 根级数字截断

    @Test
    void 截断输入只抛IllegalArgumentException() {
        for (String text : TRUNCATED) {
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject(text),
                    "应抛 IllegalArgumentException：" + text);
        }
    }

    @Test
    void 数组里的截断也只抛IllegalArgumentException() {
        for (String text : TRUNCATED) {
            assertThrows(IllegalArgumentException.class,
                    () -> Json.parseObject("{\"list\":" + text + "}"),
                    "应抛 IllegalArgumentException：" + text);
        }
    }
}
