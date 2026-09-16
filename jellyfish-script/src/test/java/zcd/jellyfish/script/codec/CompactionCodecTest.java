package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CompactionStrategy;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩策略编解码的单元测试。
 * <p>
 * <b>{@code null} 是「本插件不表态」，不是 0</b>：内核按「order 最小且声明了该字段」的那一个合并，
 * 归一成 0 会变成「明确要求保留 0 条」，语义正好相反。因此这里对两个数量字段各钉一次 null。
 *
 * @author zcd
 */
@DisplayName("压缩策略编解码")
class CompactionCodecTest {

    /** 被测 codec。 */
    private final CompactionCodec codec = new CompactionCodec();

    @Test
    @DisplayName("请求应带上触发原因与两个默认值，供插件判断该不该表态")
    void encodeRequest_should_carryTriggerAndDefaults() {
        JsonNode payload = codec.encodeRequest(new CompactionStrategyRequest("s-1", CompactionTrigger.AUTO,
                40, 10, 20, 4000, 100000L, "openai/gpt-4o"));

        assertEquals("AUTO", payload.get("trigger").asText());
        assertEquals(40, payload.get("messageCount").asInt());
        assertEquals(10, payload.get("compressedCount").asInt());
        assertEquals(20, payload.get("defaultKeepRecentMessages").asInt());
        assertEquals(4000, payload.get("defaultMaxSummaryChars").asInt());
        assertEquals(100000L, payload.get("budgetTokens").asLong());
        assertEquals("openai/gpt-4o", payload.get("modelId").asText());
        assertEquals("s-1", payload.get("sessionId").asText());
    }

    @Test
    @DisplayName("应解出摘要指令与两个数量参数")
    void decodeResult_should_readAllFields_when_payloadIsComplete() {
        CompactionStrategy strategy = codec.decodeResult(ScriptJson.tree(
                "{\"summaryPrompt\":\"压缩 {maxSummaryChars}\",\"keepRecentMessages\":20,"
                        + "\"maxSummaryChars\":4000}"), null);

        assertEquals("压缩 {maxSummaryChars}", strategy.getSummaryPrompt());
        assertEquals(20, strategy.getKeepRecentMessages());
        assertEquals(4000, strategy.getMaxSummaryChars());
    }

    @Test
    @DisplayName("缺失的数量字段应保持 null，不能被当成 0")
    void decodeResult_should_keepNull_when_countsAreAbsent() {
        CompactionStrategy strategy = codec.decodeResult(ScriptJson.tree("{\"summaryPrompt\":\"x\"}"), null);

        assertNull(strategy.getKeepRecentMessages());
        assertNull(strategy.getMaxSummaryChars());
    }

    @Test
    @DisplayName("空结果应等价于「不表态」")
    void decodeResult_should_behaveLikeNone_when_resultIsEmpty() {
        CompactionStrategy strategy = codec.decodeResult(null, null);

        assertNull(strategy.getSummaryPrompt());
        assertNull(strategy.getKeepRecentMessages());
        assertNull(strategy.getMaxSummaryChars());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(CompactionStrategyRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
    }
}
