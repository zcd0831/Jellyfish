package zcd.jellyfish.infra.tooloutput;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ToolOutputLimiter} 的单元测试。
 * <p>
 * 核心断言只有一条：<b>无论输入是文本还是结构化对象，超限后的回灌文本都必须是一段合法 JSON，
 * 且带上落盘路径</b>。这正是原实现（字符切割）做不到的事。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolOutputLimiter 工具输出中间件")
class ToolOutputLimiterTest {

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 落盘存储，用 mock 以便分别验证「有路径」与「无路径」两条分支。 */
    @Mock
    private ToolOutputStore store;

    /** 被测中间件。 */
    private ToolOutputLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ToolOutputLimiter(runtimeConfig, store);
    }

    @Test
    @DisplayName("输出为 null 时应回灌空串")
    void limit_should_returnEmpty_when_outputNull() {
        assertEquals("", limiter.limit("s", "c", "read", null));
    }

    @Test
    @DisplayName("短文本应原样回灌，不触碰磁盘")
    void limit_should_passThroughShortText() {
        maxChars(100);

        assertEquals("hello", limiter.limit("s", "c", "read", "hello"));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("短结构化结果应序列化后原样回灌")
    void limit_should_serializeShortStructured() {
        maxChars(100);

        assertEquals("[1,2]", limiter.limit("s", "c", "jira", Arrays.asList(1, 2)));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("超长文本应落盘并回灌带路径的合法信封")
    void limit_should_offloadAndEnvelope_when_textTooLong() {
        // Given
        maxChars(600);
        String text = repeat('a', 2000);
        when(store.store("s", "c", "read_file", text, false)).thenReturn("/tmp/spill.txt");

        // When
        String output = limiter.limit("s", "c", "read_file", text);

        // Then
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(output);
        assertNotNull(envelope, output);
        assertEquals("/tmp/spill.txt", envelope.getPath());
        assertEquals(2000, envelope.getTotalChars());
        assertTrue(envelope.getPreview().isTextual());
        assertTrue(envelope.getPreview().asText().length() < 2000);
    }

    @Test
    @DisplayName("超长结构化结果的回灌文本必须是合法 JSON，且预览是截断后的数组")
    void limit_should_produceValidJson_when_structuredTooLong() {
        // Given
        maxChars(600);
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < 200; i++) {
            items.add("item-" + i);
        }
        when(store.store(eq("s"), eq("c"), eq("jira"), anyString(), eq(true))).thenReturn("/tmp/spill.json");

        // When
        String output = limiter.limit("s", "c", "jira", items);

        // Then：能解析即证明没有被从中间切断
        JsonNode node = ObjectMapperWrapper.readTree(output);
        assertTrue(node.path("_truncated").asBoolean());
        assertEquals("/tmp/spill.json", node.path("_path").asText());
        assertTrue(node.path("preview").isArray());
        assertTrue(node.path("preview").size() < items.size());
    }

    @Test
    @DisplayName("落盘失败时信封应带 null 路径与不可恢复指引")
    void limit_should_reportUnrecoverable_when_storeFails() {
        // Given
        maxChars(600);
        String text = repeat('a', 2000);
        when(store.store("s", "c", "read_file", text, false)).thenReturn(null);

        // When
        String output = limiter.limit("s", "c", "read_file", text);

        // Then
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(output);
        assertNotNull(envelope, output);
        assertFalse(envelope.render().contains("/tmp"));
        assertTrue(envelope.stub().contains("不可恢复"));
        verify(store).store("s", "c", "read_file", text, false);
    }

    @Test
    @DisplayName("超长文本应保留开头与结尾，并写明省略量")
    void limit_should_keepBothEnds_when_textTooLong() {
        // Given：多行文本，头尾都能辨认
        maxChars(600);
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            source.append("line-").append(i).append('\n');
        }
        String text = source.toString();
        when(store.store("s", "c", "read_file", text, false)).thenReturn("/tmp/spill.txt");

        // When
        String output = limiter.limit("s", "c", "read_file", text);

        // Then：结论往往在末尾，只留头会让模型看到「一切正常的前 90%」
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(output);
        assertNotNull(envelope, output);
        String preview = envelope.getPreview().asText();
        assertTrue(preview.startsWith("line-0\n"), preview);
        assertTrue(preview.endsWith("line-399\n"), preview);
        assertTrue(preview.contains("省略"), preview);
        assertFalse(preview.contains("line-200\n"), preview);
    }

    @Test
    @DisplayName("回灌文本不得超过配置的字符上限（含转义膨胀）")
    void limit_should_respectMaxChars_evenWhenEscapingExpands() {
        // Given：正文里全是会被 JSON 转义的字符（引号与换行各自膨胀一倍）
        int maxChars = 800;
        maxChars(maxChars);
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            source.append("a\"b\\c").append(i).append('\n');
        }
        when(store.store(anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn("/tmp/spill.txt");

        // When
        String output = limiter.limit("s", "c", "read_file", source.toString());

        // Then：信封开销预算是估计，渲染后的校正循环必须把实际膨胀兜住
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(output);
        assertNotNull(envelope, output);
        assertTrue(output.length() <= maxChars, "长度 " + output.length());
    }

    @Test
    @DisplayName("超长数组应以「前缀 + 哨兵 + 后缀」截断，且仍是合法 JSON")
    void limit_should_keepBothEndsOfArray_withSentinel() {
        // Given
        maxChars(600);
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < 200; i++) {
            items.add("item-" + i);
        }
        when(store.store(eq("s"), eq("c"), eq("jira"), anyString(), eq(true))).thenReturn("/tmp/spill.json");

        // When
        String output = limiter.limit("s", "c", "jira", items);

        // Then
        JsonNode preview = ObjectMapperWrapper.readTree(output).path("preview");
        assertTrue(preview.isArray());
        assertTrue(preview.size() < items.size());
        assertEquals("item-0", preview.get(0).asText());
        assertEquals("item-199", preview.get(preview.size() - 1).asText());
        // 中间必须有一项显眼的哨兵：不加的话模型会把前后两段当成一份连续的数据
        boolean hasSentinel = false;
        for (JsonNode element : preview) {
            if (element.isTextual() && element.asText().contains("省略")) {
                hasSentinel = true;
            }
        }
        assertTrue(hasSentinel, preview.toString());
    }

    /**
     * 把本次生效的输出字符上限写进配置桩。
     *
     * @param maxChars 字符上限
     */
    private void maxChars(int maxChars) {
        when(runtimeConfig.getReactSettings())
                .thenReturn(new ReactSettings(null, null, maxChars, null, null, null, null));
    }

    /**
     * 生成重复字符文本。
     *
     * @param c     字符
     * @param times 次数
     * @return 文本
     */
    private static String repeat(char c, int times) {
        StringBuilder text = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            text.append(c);
        }
        return text.toString();
    }
}
