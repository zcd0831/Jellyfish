package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩策略扩展点的请求与结果类型单元测试。
 * <p>
 * 重点在<b>边界</b>：请求只能带数字与标识（插件拿不到任何一条消息正文），
 * 策略的三个字段必须能表达「这一项我不说话」。
 *
 * @author zcd
 */
class CompactionStrategyRequestTest {

    @Test
    void request_should_carryAllNumbersAndIdentifiers() {
        CompactionStrategyRequest request = new CompactionStrategyRequest("s-1", CompactionTrigger.AUTO,
                40, 12, 20, 4000, 190_000L, "gpt-4o");

        assertEquals("s-1", request.getSessionId());
        assertEquals(CompactionTrigger.AUTO, request.getTrigger());
        assertEquals(40, request.getMessageCount());
        assertEquals(12, request.getCompressedCount());
        assertEquals(20, request.getDefaultKeepRecentMessages());
        assertEquals(4000, request.getDefaultMaxSummaryChars());
        assertEquals(190_000L, request.getBudgetTokens());
        assertEquals("gpt-4o", request.getModelId());
        assertEquals(CompactionStrategy.class, request.getResultType());
    }

    @Test
    void request_should_haveNullRouteKey_because_pluginsShareOneStrategy() {
        CompactionStrategyRequest request = new CompactionStrategyRequest("s-1", CompactionTrigger.MANUAL,
                1, 0, 20, 4000, 1L, "m");

        assertNull(request.getRouteKey());
    }

    @Test
    void request_should_tolerateNullModelId_when_modelNotResolved() {
        CompactionStrategyRequest request = new CompactionStrategyRequest("s-1", CompactionTrigger.MANUAL,
                1, 0, 20, 4000, 0L, null);

        assertNull(request.getModelId());
    }

    @Test
    void strategy_should_defaultToSilenceOnEveryField() {
        CompactionStrategy strategy = CompactionStrategy.none();

        assertNull(strategy.getSummaryPrompt());
        assertNull(strategy.getKeepRecentMessages());
        assertNull(strategy.getMaxSummaryChars());
    }

    @Test
    void placeholder_should_beStableLiteral_forPluginAuthors() {
        // 插件作者按这个字面量写指令，内核按它做替换：改名就是破坏兼容
        assertEquals("{maxSummaryChars}", CompactionStrategy.MAX_CHARS_PLACEHOLDER);
    }

    @Test
    void strategy_should_keepZeroKeepRecent_asExplicitAllCompress() {
        CompactionStrategy strategy = new CompactionStrategy(null, 0, null);

        // 0 是「一条原文都不留」的合法表达，不能与 null（不表态）混为一谈
        assertEquals(0, strategy.getKeepRecentMessages());
        assertNull(strategy.getMaxSummaryChars());
    }

    @Test
    void strategy_should_notPrintLongPrompt_inToString() {
        CompactionStrategy strategy = new CompactionStrategy("很长的摘要指令", 5, 100);

        String text = strategy.toString();

        assertTrue(text.contains("summaryPromptLength"), text);
        assertTrue(!text.contains("很长的摘要指令"), text);
    }
}
