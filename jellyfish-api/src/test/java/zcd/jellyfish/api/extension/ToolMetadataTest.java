package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolMetadata} 的判据：界面渲染警告标记全靠它，因此边界必须钉死。
 *
 * @author zcd
 */
@DisplayName("ToolMetadata 元数据约定")
class ToolMetadataTest {

    @Test
    @DisplayName("退出码非零：值得警示")
    void failed_should_beTrue_when_exitCodeNonZero() {
        assertTrue(ToolMetadata.failed(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(1))));
        assertTrue(ToolMetadata.failed(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(-9))));
    }

    @Test
    @DisplayName("退出码为零：不警示（成功是常态，标出来只会埋掉真正需要看见的那几条）")
    void failed_should_beFalse_when_exitCodeZero() {
        assertFalse(ToolMetadata.failed(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(0))));
    }

    @Test
    @DisplayName("非正常终止（超时 / 取消）：值得警示，即使没有退出码")
    void failed_should_beTrue_when_terminated() {
        assertTrue(ToolMetadata.failed(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_TERMINAL, "TIMEOUT")));
        assertTrue(ToolMetadata.failed(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_TERMINAL, "CANCELLED")));
    }

    @Test
    @DisplayName("正常完成的取值不警示，缺省（缺键）也不警示")
    void failed_should_beFalse_when_completedOrAbsent() {
        assertFalse(ToolMetadata.failed(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_TERMINAL, ToolMetadata.TERMINAL_COMPLETED)));
        assertFalse(ToolMetadata.failed(Collections.<String, Object>singletonMap("durationMs", 12L)));
        assertFalse(ToolMetadata.failed(Collections.<String, Object>emptyMap()));
        assertFalse(ToolMetadata.failed(null));
    }

    @Test
    @DisplayName("坏数据一律当作「无此信息」而不是抛异常：元数据是旁路信息，写坏了不该炸掉渲染")
    void failed_should_tolerateMalformedValues() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_EXIT_CODE, "1");
        metadata.put(ToolMetadata.KEY_TERMINAL, Integer.valueOf(7));
        metadata.put("null", null);

        assertFalse(ToolMetadata.failed(metadata));
    }

    @Test
    @DisplayName("退出码用数字的字符串形式也认：脚本语言最容易给成字符串")
    void failed_should_acceptNumericTypes() {
        // 只认 Number 的子类型；字符串"1"不认——那不是「宽容」，那是替工具猜它的类型
        Map<String, Object> numeric = new HashMap<String, Object>();
        numeric.put(ToolMetadata.KEY_EXIT_CODE, Long.valueOf(3L));
        assertTrue(ToolMetadata.failed(numeric));
    }

    @Test
    @DisplayName("未知键只透传不解释：内核不认识它不等于没人认识")
    void unknownKeys_should_notAffectVerdict() {
        assertFalse(ToolMetadata.failed(Collections.<String, Object>singletonMap("anything", "value")));
        assertEquals("exitCode", ToolMetadata.KEY_EXIT_CODE);
        assertEquals("terminal", ToolMetadata.KEY_TERMINAL);
        assertEquals("summary", ToolMetadata.KEY_SUMMARY);
    }

    @Test
    @DisplayName("摘要原样取回（包括首尾空白之外的内容），去掉首尾空白")
    void summaryOf_should_returnTrimmedText() {
        assertEquals("子代理 scout · 3 轮", ToolMetadata.summaryOf(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_SUMMARY, "  子代理 scout · 3 轮  ")));
        // 不截断长度：摘要能写多长由工具自己决定，外壳负责换行
        StringBuilder longSummary = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longSummary.append("很长的摘要");
        }
        assertEquals(longSummary.toString(), ToolMetadata.summaryOf(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_SUMMARY, longSummary.toString())));
    }

    @Test
    @DisplayName("没有摘要时返回空串而不是 null：外壳据此决定要不要接逗号")
    void summaryOf_should_returnEmptyString_whenAbsent() {
        assertEquals("", ToolMetadata.summaryOf(null));
        assertEquals("", ToolMetadata.summaryOf(Collections.<String, Object>emptyMap()));
        assertEquals("", ToolMetadata.summaryOf(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_SUMMARY, "   ")));
    }

    @Test
    @DisplayName("摘要写坏了一律当作「无此信息」：与 failed 同一套宽容原则")
    void summaryOf_should_tolerateMalformedValues() {
        assertEquals("", ToolMetadata.summaryOf(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_SUMMARY, Integer.valueOf(7))));
        assertEquals("", ToolMetadata.summaryOf(Collections.<String, Object>singletonMap(
                ToolMetadata.KEY_SUMMARY, null)));
    }

    @Test
    @DisplayName("摘要是展示用的事实，不参与成败判据")
    void summary_should_notAffectFailureVerdict() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_SUMMARY, "子代理 scout · 3 轮 · 结论不完整");

        assertFalse(ToolMetadata.failed(metadata));
    }
}
