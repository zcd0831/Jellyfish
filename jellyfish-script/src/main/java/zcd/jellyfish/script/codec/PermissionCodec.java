package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVeto;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 权限拦截编解码：{@code PermissionCheckRequest} ↔ {@code PermissionVeto}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"agentId":"coder","toolName":"write_file","arguments":{...},
 *              "mode":"NORMAL","sessionId":"s-1"}
 *   result  : {"denied":true,"reason":"禁止写入 .env"}     // denied=false 即无异议
 * </pre>
 * <b>只有两态，且这是编译期约束</b>：{@link PermissionVeto} 没有「要求审批」这一态，
 * 因此「插件只能拒绝、不能要求审批」不是靠文档约定的纪律，而是类型上就做不到。
 * 脚本侧同理——它拿不到审批通道，也不该拿到（审批者只能是外壳）。
 * <p>
 * <b>失败按「无异议」处理</b>：处理器抛错（含脚本超时）时 {@code PermissionManager.intercept}
 * 只记 WARN 并视为没有意见，与 Java 插件拦截器完全同权。这是刻意的 fail-open 取舍——
 * 一个插件的故障不该让整条工具调用链崩掉；真正的把关仍在核心策略与 PLAN 白名单上。
 * <p>
 * 内核在核心策略已经拒绝时<b>根本不会调用</b>本扩展点（结果不可能更宽），因此脚本不必处理这种情况。
 *
 * @author zcd
 */
public final class PermissionCodec implements ExtensionCodec<PermissionCheckRequest, PermissionVeto> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "permission";

    /** 结果载荷里的拒绝标记字段名。 */
    private static final String FIELD_DENIED = "denied";

    /** 结果载荷里的理由字段名。 */
    private static final String FIELD_REASON = "reason";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<PermissionCheckRequest> requestType() {
        return PermissionCheckRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 多个插件都可表态：内核用 bindings(PermissionCheckRequest.class, null) 按 order 依次问
        return true;
    }

    @Override
    public JsonNode encodeRequest(PermissionCheckRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("agentId", request.getAgentId());
        payload.put("toolName", request.getToolName());
        payload.put("arguments", request.getArguments());
        payload.put("mode", request.getMode() == null ? null : request.getMode().name());
        payload.put("sessionId", request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public PermissionVeto decodeResult(JsonNode result, String routeKey) {
        if (!Payloads.bool(result, FIELD_DENIED, false)) {
            return PermissionVeto.none();
        }
        return PermissionVeto.deny(Payloads.text(result, FIELD_REASON));
    }
}
