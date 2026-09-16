package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 具名命令编解码：{@code CommandRequest} ↔ {@code CommandResult}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"command":"jira","arguments":{"tokens":["PROJ-1"],"raw":"PROJ-1"},"sessionId":"s-1"}
 *   result  : {"kind":"OK","output":"已更新","choices":[{"value":"PROJ-1","label":"...","current":true}]}
 * </pre>
 * <b>参数由内核切分后下发</b>（{@code tokens} + {@code raw} 两种视图并存），脚本不需要、
 * 也不应该自己拆命令行——重新拼接必然丢信息（连续空格、原引号形态）。
 * <p>
 * <b>三态必须如实回传</b>：{@code UNKNOWN}（没有这条命令）与 {@code ERROR}（命令存在但这次没成）
 * 对用户是两种不同的处置，前者会让外壳提示 {@code /help}、后者只报失败。因此在编解码层就把它们
 * 分开，而不是都塞成失败文案。收到无法识别的 {@code kind} 时直接报错，不静默降级——
 * 那说明脚本与内核的协议已经不一致了。
 *
 * @author zcd
 */
public final class CommandCodec implements ExtensionCodec<CommandRequest, CommandResult> {

    /** 协议类型名。 */
    public static final String TYPE_NAME = "command";

    /** 结果载荷里的状态字段名。 */
    private static final String FIELD_KIND = "kind";

    /** 结果载荷里的文本字段名。 */
    private static final String FIELD_OUTPUT = "output";

    /** 结果载荷里的候选字段名。 */
    private static final String FIELD_CHOICES = "choices";

    /** 缺省状态：脚本只给 output 时按「已执行」处理。 */
    private static final String KIND_OK = "OK";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<CommandRequest> requestType() {
        return CommandRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 命令名即路由键，同键唯一：内核用 handler(CommandRequest.class, 命令名) 查
        return false;
    }

    @Override
    public JsonNode encodeRequest(CommandRequest request) {
        CommandArguments arguments = request.getArguments();
        Map<String, Object> argumentPayload = new LinkedHashMap<String, Object>();
        argumentPayload.put("tokens", arguments.getTokens());
        argumentPayload.put("raw", arguments.getRaw());

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("command", request.getName());
        payload.put("arguments", argumentPayload);
        payload.put("sessionId", request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public CommandResult decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return CommandResult.ok(null);
        }
        JsonNode output = result.get(FIELD_OUTPUT);
        String text = output == null || !output.isTextual() ? null : output.asText();
        JsonNode kindNode = result.get(FIELD_KIND);
        String kind = kindNode == null || !kindNode.isTextual() ? KIND_OK : kindNode.asText();
        if (CommandResult.Kind.OK.name().equals(kind)) {
            List<CommandChoice> choices = Payloads.choices(result.get(FIELD_CHOICES));
            return choices.isEmpty() ? CommandResult.ok(text) : CommandResult.choices(text, choices);
        }
        if (CommandResult.Kind.ERROR.name().equals(kind)) {
            return CommandResult.error(text);
        }
        if (CommandResult.Kind.UNKNOWN.name().equals(kind)) {
            return CommandResult.unknown(text);
        }
        throw new JellyfishException("命令结果 kind 非法: " + kind + "（command=" + routeKey + "）");
    }
}
