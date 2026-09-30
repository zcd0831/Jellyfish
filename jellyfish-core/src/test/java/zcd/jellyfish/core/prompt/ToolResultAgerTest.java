package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.infra.config.ReactCacheSettings;
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
 * {@link ToolResultAger} 的单元测试：锁住「只老化较早的截断信封、近的与非信封一律不动」，
 * 以及水位口径下「边界在一个压缩周期内冻住不动」——后者才是 P3 的全部意义所在。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolResultAger 工具结果老化")
class ToolResultAgerTest {

    /** 测试用会话标识。 */
    private static final String SESSION = "s-1";

    /** 未压缩会话的边界下标。 */
    private static final int NO_BOUNDARY = -1;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 被测老化器。 */
    private ToolResultAger ager;

    @BeforeEach
    void setUp() {
        ager = new ToolResultAger(runtimeConfig);
    }

    // ==================== 旧口径（agingPercent = 0，按距尾部条数） ====================

    @Test
    @DisplayName("较早的信封应替换成带路径的 stub")
    void age_should_replaceOldEnvelopeWithStub() {
        // Given：保留最近 1 条，因此第 0 条工具结果是「较早的」
        configure(1, 0);
        LlmMessage oldTool = LlmMessage.tool("c1", "read", envelope());
        LlmMessage recentUser = LlmMessage.user("hi");

        // When
        List<LlmMessage> aged = ager.age(SESSION, NO_BOUNDARY, ContextUsage.unknown(),
                Arrays.asList(oldTool, recentUser));

        // Then
        assertEquals(2, aged.size());
        assertTrue(aged.get(0).getContent().contains("/tmp/spill.txt"), aged.get(0).getContent());
        assertEquals("hi", aged.get(1).getContent());
    }

    @Test
    @DisplayName("老化后仍保留结论行：命令结果的退出码不能因为老化而消失")
    void age_should_keepConclusionLine() {
        // Given：shell 把结论放在正文首行，而落盘文件里只有正文
        configure(1, 0);
        String content = ToolOutputEnvelope.text("shell", 1000, 1, "/tmp/spill.txt", "hint",
                "cwd: /repo · exit: 1\n构建失败").render();
        LlmMessage oldTool = LlmMessage.tool("c1", "shell", content);

        // When
        List<LlmMessage> aged = ager.age(SESSION, NO_BOUNDARY, ContextUsage.unknown(),
                Arrays.asList(oldTool, LlmMessage.user("hi")));

        // Then
        assertTrue(aged.get(0).getContent().contains("exit: 1"), aged.get(0).getContent());
    }

    @Test
    @DisplayName("落在保留窗口内的信封应保持完整")
    void age_should_keepRecentEnvelope() {
        configure(1, 0);
        LlmMessage recentTool = LlmMessage.tool("c1", "read", envelope());

        List<LlmMessage> aged = ager.age(SESSION, NO_BOUNDARY, ContextUsage.unknown(),
                Collections.singletonList(recentTool));

        assertEquals(envelope(), aged.get(0).getContent());
    }

    @Test
    @DisplayName("普通工具结果不是信封，应原样返回同一份列表")
    void age_should_ignorePlainToolResult() {
        configure(1, 0);
        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.tool("c1", "read", "普通结果"), LlmMessage.user("hi"));

        List<LlmMessage> aged = ager.age(SESSION, NO_BOUNDARY, ContextUsage.unknown(), messages);

        assertSame(messages, aged);
    }

    @Test
    @DisplayName("保留条数为 0 表示关闭老化，列表原样返回")
    void age_should_returnUnchanged_when_disabled() {
        configure(0, 0);
        LlmMessage oldTool = LlmMessage.tool("c1", "read", envelope());
        List<LlmMessage> messages = Collections.singletonList(oldTool);

        assertSame(messages, ager.age(SESSION, NO_BOUNDARY, ContextUsage.unknown(), messages));
    }

    // ==================== 水位口径（agingPercent > 0） ====================

    @Test
    @DisplayName("水位未到就不老化：用量低时旧信封保持完整")
    void age_should_keepEverything_whenWatermarkNotReached() {
        // Given：水位 50%，而当前只用到 10%
        configure(1, 50);
        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.tool("c1", "read", envelope()), LlmMessage.user("hi"));

        // When
        List<LlmMessage> aged = ager.age(SESSION, NO_BOUNDARY, usage(10), messages);

        // Then：原样返回同一份列表，一个字节都没改
        assertSame(messages, aged);
    }

    @Test
    @DisplayName("水位一到就一次性老化到保留窗口，而不是只挪一条")
    void age_should_ageUpToWindow_whenWatermarkReached() {
        // Given：水位 50%，用量 60%
        configure(2, 50);
        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.tool("c1", "read", envelope()),
                LlmMessage.tool("c2", "read", envelope()),
                LlmMessage.user("hi"),
                LlmMessage.user("再来"));

        // When
        List<LlmMessage> aged = ager.age(SESSION, NO_BOUNDARY, usage(60), messages);

        // Then：保留窗口是 2，因此前两条信封被换掉，后两条原样
        assertTrue(aged.get(0).getContent().contains("/tmp/spill.txt"), aged.get(0).getContent());
        assertTrue(aged.get(1).getContent().contains("/tmp/spill.txt"), aged.get(1).getContent());
        assertEquals("hi", aged.get(2).getContent());
        assertEquals("再来", aged.get(3).getContent());
    }

    @Test
    @DisplayName("跨越一次之后边界冻住：会话继续变长也不会再老化，这正是 P3 的全部意义")
    void age_should_freezeFrontier_afterFirstCrossing() {
        // Given：水位 50%，保留窗口 1
        configure(1, 50);
        LlmMessage first = LlmMessage.tool("c1", "read", envelope());
        LlmMessage second = LlmMessage.tool("c2", "read", envelope());

        // When：第一轮用量 60% → 跨过水位，第 0 条被老化
        List<LlmMessage> firstTurn = ager.age(SESSION, NO_BOUNDARY, usage(60),
                Arrays.asList(first, LlmMessage.user("hi")));
        assertTrue(firstTurn.get(0).getContent().contains("/tmp/spill.txt"),
                firstTurn.get(0).getContent());

        // 第二轮：会话又长出两条，且用量仍在 70%（依旧高于水位）
        List<LlmMessage> secondTurn = ager.age(SESSION, NO_BOUNDARY, usage(70),
                Arrays.asList(first, LlmMessage.user("hi"), second, LlmMessage.user("再来")));

        // Then：第 2 条**没有**被老化。按旧口径（size - keepRecent = 3）它本该被换掉，
        // 而每轮这样前移 2 条，恰好把最近、最贵、刚被缓存的那一段逐轮作废
        assertEquals(envelope(), secondTurn.get(2).getContent());
    }

    @Test
    @DisplayName("压缩过后重新累计：边界变了就允许再推进一次")
    void age_should_allowOneMoreCrossing_afterBoundaryChanges() {
        // Given
        configure(1, 50);
        LlmMessage first = LlmMessage.tool("c1", "read", envelope());
        LlmMessage second = LlmMessage.tool("c2", "read", envelope());
        ager.age(SESSION, NO_BOUNDARY, usage(60), Arrays.asList(first, LlmMessage.user("hi")));

        // When：压缩发生，边界从 -1 变成 5，且用量仍在水位之上
        List<LlmMessage> after = ager.age(SESSION, 5, usage(70),
                Arrays.asList(second, LlmMessage.user("压缩之后")));

        // Then：新周期里第 0 条被老化——否则老化会一直失效到会话结束
        assertTrue(after.get(0).getContent().contains("/tmp/spill.txt"), after.get(0).getContent());
    }

    @Test
    @DisplayName("会话标识缺失时退化为只看本轮用量，不跨轮保持边界")
    void age_should_notRememberFrontier_whenSessionUnknown() {
        // Given
        configure(1, 50);
        LlmMessage first = LlmMessage.tool("c1", "read", envelope());
        LlmMessage second = LlmMessage.tool("c2", "read", envelope());
        ager.age(null, NO_BOUNDARY, usage(60), Arrays.asList(first, LlmMessage.user("hi")));

        // When：没有键就无从归属，因此每次都按当前用量重新判断
        List<LlmMessage> aged = ager.age(null, NO_BOUNDARY, usage(70),
                Arrays.asList(first, LlmMessage.user("hi"), second, LlmMessage.user("再来")));

        // Then：按当前 size 老化到保留窗口
        assertTrue(aged.get(2).getContent().contains("/tmp/spill.txt"), aged.get(2).getContent());
    }

    /**
     * 把两项配置写进配置桩。
     *
     * @param keepRecent   保留的最近消息条数
     * @param agingPercent 老化触发水位线，{@code 0} 表示旧口径
     */
    private void configure(int keepRecent, int agingPercent) {
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(
                null, null, null, null, null, null,
                new ToolOutputSettings(null, null, null, null, keepRecent),
                new ReactCacheSettings(agingPercent)));
    }

    /**
     * 构造一个「已用 {@code percent}%」的用量。
     * <p>
     * 预算取 {@code 100}，于是 {@code used} 与百分比数值相同，读起来就是「用到百分之几」。
     *
     * @param percent 已用百分比
     * @return 用量
     */
    private static ContextUsage usage(int percent) {
        return new ContextUsage(percent, 100, false);
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
