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
