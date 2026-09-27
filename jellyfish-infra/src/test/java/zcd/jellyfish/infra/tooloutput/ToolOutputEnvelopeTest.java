package zcd.jellyfish.infra.tooloutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
