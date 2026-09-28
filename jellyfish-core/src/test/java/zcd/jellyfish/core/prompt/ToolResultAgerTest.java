package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.ToolOutputSettings;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.tooloutput.ToolOutputEnvelope;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link ToolResultAger} 的单元测试：锁住「只老化较早的截断信封、近的与非信封一律不动」。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolResultAger 工具结果老化")
class ToolResultAgerTest {

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 被测老化器。 */
    private ToolResultAger ager;

    @BeforeEach
    void setUp() {
        ager = new ToolResultAger(runtimeConfig);
    }

    @Test
    @DisplayName("较早的信封应替换成带路径的 stub")
    void age_should_replaceOldEnvelopeWithStub() {
        // Given：保留最近 1 条，因此第 0 条工具结果是「较早的」
        keepRecent(1);
        LlmMessage oldTool = LlmMessage.tool("c1", "read", envelope());
        LlmMessage recentUser = LlmMessage.user("hi");

        // When
        List<LlmMessage> aged = ager.age(Arrays.asList(oldTool, recentUser));

        // Then
        assertEquals(2, aged.size());
        assertTrue(aged.get(0).getContent().contains("/tmp/spill.txt"), aged.get(0).getContent());
        assertEquals("hi", aged.get(1).getContent());
    }

    @Test
    @DisplayName("老化后仍保留结论行：命令结果的退出码不能因为老化而消失")
    void age_should_keepConclusionLine() {
        // Given：shell 把结论放在正文首行，而落盘文件里只有正文
        keepRecent(1);
        String content = ToolOutputEnvelope.text("shell", 1000, 1, "/tmp/spill.txt", "hint",
                "cwd: /repo · exit: 1\n构建失败").render();
        LlmMessage oldTool = LlmMessage.tool("c1", "shell", content);

        // When
        List<LlmMessage> aged = ager.age(Arrays.asList(oldTool, LlmMessage.user("hi")));

        // Then
        assertTrue(aged.get(0).getContent().contains("exit: 1"), aged.get(0).getContent());
    }

    @Test
    @DisplayName("落在保留窗口内的信封应保持完整")
    void age_should_keepRecentEnvelope() {
        keepRecent(1);
        LlmMessage recentTool = LlmMessage.tool("c1", "read", envelope());

        List<LlmMessage> aged = ager.age(Collections.singletonList(recentTool));

        assertEquals(envelope(), aged.get(0).getContent());
    }

    @Test
    @DisplayName("普通工具结果不是信封，应原样返回同一份列表")
    void age_should_ignorePlainToolResult() {
        keepRecent(1);
        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.tool("c1", "read", "普通结果"), LlmMessage.user("hi"));

        List<LlmMessage> aged = ager.age(messages);

        assertSame(messages, aged);
    }

    @Test
    @DisplayName("保留条数为 0 表示关闭老化，列表原样返回")
    void age_should_returnUnchanged_when_disabled() {
        keepRecent(0);
        LlmMessage oldTool = LlmMessage.tool("c1", "read", envelope());
        List<LlmMessage> messages = Collections.singletonList(oldTool);

        assertSame(messages, ager.age(messages));
    }

    /**
     * 把保留条数写进配置桩。
     *
     * @param keepRecent 保留的最近消息条数
     */
    private void keepRecent(int keepRecent) {
        when(runtimeConfig.getReactSettings()).thenReturn(
                new ReactSettings(null, null, null, null, null, null,
                        new ToolOutputSettings(null, null, null, null, keepRecent)));
    }

    /**
     * 构造一份示例截断信封。
     *
     * @return 信封文本
     */
    private static String envelope() {
        return ToolOutputEnvelope.text("read", 1000, 1, "/tmp/spill.txt", "hint", "preview").render();
    }
}
