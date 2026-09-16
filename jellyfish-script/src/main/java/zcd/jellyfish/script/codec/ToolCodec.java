package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用编解码：{@code ToolCallRequest} ↔ {@code ToolCallResult}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"tool":"jira_issue","arguments":{...},"sessionId":"s-1"}
 *   result  : {"output": 任意 JSON 值}
 * </pre>
 * <b>{@code output} 的翻译规则与内核完全对齐</b>（{@code ReActLooper.serializeOutput}）：
 * 字符串原样回灌，其它 JSON 值由内核序列化成 JSON 文本，{@code null} 回灌空串。
 * 因此这里必须把节点还原成「字符串就是字符串、对象就是 Map」的形态，而不能一律转成文本——
 * 否则脚本返回结构化数据时会被二次转义，模型看到的是转义后的字符串。
 * <p>
 * <b>结果形状的宽容度</b>：结果是对象时必须有 {@code output} 键（缺失或 {@code null} 都表示空输出）；
 * 结果不是对象时（裸字符串、数字、数组）整个结果就是输出值。后者是给「忘记包一层」的脚本留的
 * 安全网，代价是对象形状下 {@code output} 成了保留键——想返回一个字面含 {@code output} 的对象，
 * 请写成 {@code {"output": {"output": ...}}}。
 *
 * @author zcd
 */
public final class ToolCodec implements ExtensionCodec<ToolCallRequest, ToolCallResult> {

    /** 协议类型名。 */
    public static final String TYPE_NAME = "tool";

    /** 结果载荷里的输出字段名。 */
    static final String FIELD_OUTPUT = "output";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<ToolCallRequest> requestType() {
        return ToolCallRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 工具名即路由键，同键唯一：内核用 handler(ToolCallRequest.class, 工具名) 查
        return false;
    }

    @Override
    public JsonNode encodeRequest(ToolCallRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tool", request.getToolName());
        payload.put("arguments", request.getArguments());
        payload.put("sessionId", request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public ToolCallResult decodeResult(JsonNode result, String routeKey) {
        JsonNode output = result != null && result.isObject() ? result.get(FIELD_OUTPUT) : result;
        Object value = output == null || output.isNull() ? null : ScriptJson.treeToValue(output, Object.class);
        return new ToolCallResult(routeKey, value);
    }
}
