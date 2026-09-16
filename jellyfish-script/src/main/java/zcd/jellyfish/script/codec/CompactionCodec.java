package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.CompactionStrategy;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压缩策略编解码：{@code CompactionStrategyRequest} ↔ {@code CompactionStrategy}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","trigger":"AUTO","messageCount":40,"compressedCount":10,
 *              "defaultKeepRecentMessages":20,"defaultMaxSummaryChars":4000,
 *              "budgetTokens":100000,"modelId":"openai/gpt-4o"}
 *   result  : {"summaryPrompt":"…{maxSummaryChars}…","keepRecentMessages":20,"maxSummaryChars":4000}
 * </pre>
 * <b>{@code null} 是「本插件不表态」，不是 0</b>：内核按「{@code order} 最小且声明了该字段」的那一个
 * 合并两个数量参数，归一成 0 会变成「明确要求保留 0 条」，语义正好相反。因此解析必须保留可空性。
 * <p>
 * <b>数值由内核钳制</b>（保留条数落在 {@code [0, 消息总数]}、摘要上限落在 {@code [200, 20000]}），
 * 脚本不需要也不应该自己夹紧——它不知道消息总数。
 * <p>
 * <b>摘要指令里的占位符</b>：{@code {maxSummaryChars}} 由内核替换；缺占位符只告警不失败。
 * <p>
 * <b>压缩是插件能力、内核只提供机制</b>：没有任何处理器时压缩整体不可用（不回退内置），
 * 因此脚本插件在这里缺席是完全正常的状态。
 * <p>
 * <b>处理器必须只读且快，不得发布事件</b>——它在每一轮组装上下文的路径上被调用。
 *
 * @author zcd
 */
public final class CompactionCodec implements ExtensionCodec<CompactionStrategyRequest, CompactionStrategy> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "compaction";

    /** 摘要指令字段名。 */
    private static final String FIELD_SUMMARY_PROMPT = "summaryPrompt";

    /** 保留条数字段名。 */
    private static final String FIELD_KEEP_RECENT = "keepRecentMessages";

    /** 摘要上限字段名。 */
    private static final String FIELD_MAX_SUMMARY_CHARS = "maxSummaryChars";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<CompactionStrategyRequest> requestType() {
        return CompactionStrategyRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 多个插件都可表态：内核用 bindings(CompactionStrategyRequest.class, null) 按 order 合并
        return true;
    }

    @Override
    public JsonNode encodeRequest(CompactionStrategyRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", request.getSessionId());
        payload.put("trigger", request.getTrigger() == null ? null : request.getTrigger().name());
        payload.put("messageCount", request.getMessageCount());
        payload.put("compressedCount", request.getCompressedCount());
        payload.put("defaultKeepRecentMessages", request.getDefaultKeepRecentMessages());
        payload.put("defaultMaxSummaryChars", request.getDefaultMaxSummaryChars());
        payload.put("budgetTokens", request.getBudgetTokens());
        payload.put("modelId", request.getModelId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public CompactionStrategy decodeResult(JsonNode result, String routeKey) {
        return new CompactionStrategy(Payloads.text(result, FIELD_SUMMARY_PROMPT),
                Payloads.integer(result, FIELD_KEEP_RECENT),
                Payloads.integer(result, FIELD_MAX_SUMMARY_CHARS));
    }
}
