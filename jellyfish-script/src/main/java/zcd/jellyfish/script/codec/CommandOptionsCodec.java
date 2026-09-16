package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令候选查询编解码：{@code CommandOptionRequest} ↔ {@code CommandOptions}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"command":"jira","sessionId":"s-1"}
 *   result  : {"choices":[{"value":"PROJ-1","label":"PROJ-1 修复登录","current":true}]}
 * </pre>
 * <b>这是只读路径</b>：问「这条命令有哪些取值」，不执行命令。内核刻意给它独立的请求与结果类型，
 * 就是为了让「选中命令弹选择页」看起来不像发生过副作用（{@code /new} 之类建会话的命令一旦被
 * 「顺便执行」一次，用户就会发现多出一个会话）。
 * <p>
 * 没有候选时返回空数组即可；外壳会退化成提示用法。
 *
 * @author zcd
 */
public final class CommandOptionsCodec implements ExtensionCodec<CommandOptionRequest, CommandOptions> {

    /** 协议类型名。 */
    public static final String TYPE_NAME = "command_options";

    /** 结果载荷里的候选字段名。 */
    private static final String FIELD_CHOICES = "choices";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<CommandOptionRequest> requestType() {
        return CommandOptionRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 命令名即路由键，一命令一候选处理器：内核用 handler(CommandOptionRequest.class, 命令名) 查
        return false;
    }

    @Override
    public JsonNode encodeRequest(CommandOptionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("command", request.getName());
        payload.put("sessionId", request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public CommandOptions decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return CommandOptions.empty();
        }
        return CommandOptions.of(Payloads.choices(result.get(FIELD_CHOICES)));
    }
}
