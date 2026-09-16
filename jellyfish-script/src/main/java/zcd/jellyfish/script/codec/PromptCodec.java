package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词贡献编解码：{@code PromptContributionRequest} ↔ {@code PromptContribution}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1"}
 *   result  : {"text":"待办 2/5：…"}      // null 或缺省 = 本轮没有要贡献的
 * </pre>
 * <b>这是类型级扩展点</b>：同一会话允许多个插件各贡献一段，内核按 {@code order} 升序拼接，
 * 用 {@code "\n\n"} 连接后<b>只进 system prompt</b>。它不会被追加进消息列表——那会被当成对话历史，
 * 每轮重复累积，回放与 token 统计都会失真。
 * <p>
 * 返回空贡献是正当用法：插件每轮都会被问到，只有真的有话要说时才返回文本。
 *
 * @author zcd
 */
public final class PromptCodec implements ExtensionCodec<PromptContributionRequest, PromptContribution> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "prompt";

    /** 结果载荷里的文本字段名。 */
    private static final String FIELD_TEXT = "text";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<PromptContributionRequest> requestType() {
        return PromptContributionRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 多个插件共存：内核用 bindings(PromptContributionRequest.class, null) 收集
        return true;
    }

    @Override
    public JsonNode encodeRequest(PromptContributionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public PromptContribution decodeResult(JsonNode result, String routeKey) {
        return PromptContribution.of(Payloads.text(result, FIELD_TEXT));
    }
}
