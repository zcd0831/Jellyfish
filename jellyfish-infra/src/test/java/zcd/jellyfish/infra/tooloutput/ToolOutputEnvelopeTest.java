package zcd.jellyfish.infra.tooloutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolOutputEnvelope} 的单元测试：锁住「渲染出来的必须是可解析的合法 JSON」这条契约。
 * <p>
 * 它存在的理由就是修掉「JSON 被从中间切断」的问题，因此往返与容错是这个类唯一值得测的东西。
 *
 * @author zcd
 */
@DisplayName("ToolOutputEnvelope 截断信封")
class ToolOutputEnvelopeTest {

    @Test
    @DisplayName("文本信封应可往返：总量、路径与预览都不丢")
    void render_should_roundTripTextPreview() {
        // Given
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("read_file", 1000, 42, "/tmp/spill.txt", "hint", "abc");

        // When
        ToolOutputEnvelope parsed = ToolOutputEnvelope.parse(envelope.render());

        // Then
        assertNotNull(parsed);
        assertEquals("read_file", parsed.getToolName());
        assertEquals(1000, parsed.getTotalChars());
        assertEquals(42, parsed.getTotalLines());
        assertEquals("/tmp/spill.txt", parsed.getPath());
        assertTrue(parsed.getPreview().isTextual());
        assertEquals("abc", parsed.getPreview().asText());
    }

    @Test
    @DisplayName("部分落盘应在信封与 stub 里都如实标注")
    void text_should_carryPartialFlag() {
        // Given：捕获期溢出且触及落盘上限
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("shell", 5000, 10, "/tmp/spill.txt",
                ToolOutputEnvelope.hint("/tmp/spill.txt", true), "preview", true);

        // When
        ToolOutputEnvelope parsed = ToolOutputEnvelope.parse(envelope.render());

        // Then：不讲清楚的话，「完整内容在 path」就是一句假话
        assertNotNull(parsed);
        assertTrue(parsed.isPartial());
        assertTrue(parsed.stub().contains("不完整"), parsed.stub());
    }

    @Test
    @DisplayName("恢复指引应区分完整落盘、部分落盘与落盘失败三种情形")
    void hint_should_describeRecoveryPath() {
        assertTrue(ToolOutputEnvelope.hint("/tmp/a.txt").contains("完整内容已落盘"));
        assertTrue(ToolOutputEnvelope.hint("/tmp/a.txt", true).contains("未捕获"));
        assertTrue(ToolOutputEnvelope.hint(null).contains("不可写"));
    }

    @Test
    @DisplayName("结构化信封应可往返：预览保持数组形态")
    void render_should_roundTripStructuredPreview() {
        // Given
        ToolOutputEnvelope envelope = ToolOutputEnvelope.structured("jira", 5000, "/tmp/spill.json", "hint",
                ObjectMapperWrapper.readTree("[1,2,3]"));

        // When
        ToolOutputEnvelope parsed = ToolOutputEnvelope.parse(envelope.render());

        // Then
        assertNotNull(parsed);
        assertTrue(parsed.getPreview().isArray());
        assertEquals(3, parsed.getPreview().size());
    }

    @Test
    @DisplayName("普通文本不是信封，必须安静地返回 null")
    void parse_should_returnNull_when_plainText() {
        assertNull(ToolOutputEnvelope.parse("just a normal result"));
        assertNull(ToolOutputEnvelope.parse(null));
    }

    @Test
    @DisplayName("没有 _truncated 标记的 JSON 不是信封")
    void parse_should_returnNull_when_flagMissing() {
        assertNull(ToolOutputEnvelope.parse("{\"output\":\"value\"}"));
    }

    @Test
    @DisplayName("stub 有路径时应带上回查路径")
    void stub_should_includePath_when_pathPresent() {
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("read_file", 1000, 1, "/tmp/spill.txt", "hint", "x");

        String stub = envelope.stub();

        assertTrue(stub.contains("read_file"), stub);
        assertTrue(stub.contains("1000"), stub);
        assertTrue(stub.contains("/tmp/spill.txt"), stub);
    }

    @Test
    @DisplayName("落盘失败时 stub 应明说不可恢复，而不是假装有路径")
    void stub_should_sayUnrecoverable_when_pathMissing() {
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("read_file", 1000, 1, null, "hint", "x");

        String stub = envelope.stub();

        assertTrue(stub.contains("不可恢复"), stub);
    }

    @Test
    @DisplayName("stub 应保留预览首行：结论行只存在于正文里，落盘文件没有它")
    void stub_should_keepFirstPreviewLine_when_previewIsText() {
        // Given：shell 的结论行是正文首行，而落盘文件里只有正文之后的输出
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("shell", 5000, 10, "/tmp/spill.txt", "hint",
                "cwd: /repo · exit: 1 · 耗时: 42.3s\n正文第一行\n正文第二行");

        // When
        String stub = envelope.stub();

        // Then：老化之后仍要能回答「这条命令成没成」
        assertTrue(stub.contains("cwd: /repo · exit: 1"), stub);
        assertFalse(stub.contains("正文第一行"), stub);
    }

    @Test
    @DisplayName("stub 模板：占位符被替换，不留下花括号")
    void stub_should_replacePlaceholders_when_templateGiven() {
        // Given
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("shell", 5000, 10, "/tmp/spill.txt", "hint",
                "exit: 0\n正文");

        // When
        String stub = envelope.stub("{tool}|{chars}|{lines}|{firstLine}|{recovery}");

        // Then
        assertTrue(stub.startsWith("shell|5000|10| · 首行：exit: 0|"), stub);
        assertTrue(stub.contains("/tmp/spill.txt"), stub);
        assertFalse(stub.contains("{"), stub);
    }

    @Test
    @DisplayName("stub 模板：没有首行时该段为空，不会留下一个孤立的分隔符")
    void stub_should_renderEmptyFirstLine_when_previewIsStructured() {
        // Given：结构化预览没有「结论行」这个概念
        ToolOutputEnvelope envelope = ToolOutputEnvelope.structured("jira", 5000, "/tmp/spill.json", "hint",
                ObjectMapperWrapper.readTree("[1,2,3]"), false);

        // When
        String stub = envelope.stub("头{tool}-{firstLine}-尾");

        // Then
        assertEquals("头jira--尾", stub);
    }

    @Test
    @DisplayName("stub 模板：空白模板退回内核缺省，而不是产出一行空话")
    void stub_should_fallBackToDefault_when_templateBlank() {
        // Given
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("read_file", 1000, 1, "/tmp/spill.txt", "hint", "x");

        // When
        String blank = envelope.stub("   ");

        // Then
        assertEquals(envelope.stub(), blank);
        assertTrue(blank.contains("[工具结果已省略]"), blank);
    }

    @Test
    @DisplayName("stub 模板：不认识的花括号原样保留，不报错")
    void stub_should_keepUnknownPlaceholders_asIs() {
        // Given：为一个多写的花括号就让整行文案不可用，不值得
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("read_file", 1000, 1, "/tmp/spill.txt", "hint", "x");

        // When
        String stub = envelope.stub("{tool} {未知}");

        // Then
        assertEquals("read_file {未知}", stub);
    }

    @Test
    @DisplayName("结构化预览没有「结论行」这个概念，stub 不取首行")
    void stub_should_notKeepFirstLine_when_previewIsStructured() {
        // Given
        ToolOutputEnvelope envelope = ToolOutputEnvelope.structured("jira", 5000, "/tmp/spill.json", "hint",
                ObjectMapperWrapper.readTree("[1,2,3]"), false);

        // When
        String stub = envelope.stub();

        // Then
        assertFalse(stub.contains("首行"), stub);
    }

    @Test
    @DisplayName("首行过长时 stub 必须截断：否则老化省下来的上下文又还回去了")
    void stub_should_capFirstLine_when_lineTooLong() {
        // Given：一行十万字符的输出（例如单行 JSON）
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            line.append('x');
        }
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text("read_file", 100000, 1, "/tmp/spill.txt",
                "hint", line.toString());

        // When
        String stub = envelope.stub();

        // Then
        assertTrue(stub.length() < 1000, "stub 长度 " + stub.length());
        assertTrue(stub.contains("…"), stub);
    }

    @Test
    @DisplayName("预览首行为空时不添油加醋")
    void stub_should_notAddLine_when_firstLineEmpty() {
        // Given：没有元数据行的工具（结构化结果或首行就是空行）
        ToolOutputEnvelope text = ToolOutputEnvelope.text("read_file", 1000, 1, "/tmp/a.txt", "hint", "");
        ToolOutputEnvelope structured = ToolOutputEnvelope.structured("jira", 1000, "/tmp/a.json", "hint",
                ObjectMapperWrapper.readTree("{}"));

        // Then
        assertFalse(text.stub().contains("首行"), text.stub());
        assertFalse(structured.stub().contains("首行"), structured.stub());
    }
}
