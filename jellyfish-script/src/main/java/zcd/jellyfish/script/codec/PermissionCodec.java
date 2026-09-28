package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 权限拦截编解码：{@code PermissionCheckRequest} ↔ {@code PermissionVerdict}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"agentId":"coder","toolName":"write_file","arguments":{...},
 *              "mode":"NORMAL","sessionId":"s-1"}
 *   result  : {"verdict":"ABSTAIN|ASK|DENY","reason":"禁止写入 .env"}
 * </pre>
 * <b>三态里没有「放行」</b>：{@link PermissionVerdict} 没有 ALLOW 这一态，脚本也拿不到审批通道，
 * 因此「插件只能收紧、不能放宽」不是靠文档约定的纪律，而是类型上就做不到。{@code ASK} 的含义是
 * 「把我拦不住的东西交给人在场时看一眼」，它只可能让调用更严——最终仍要过审批通道。
 * <p>
 * <b>未知取值一律报错而不是静默当成 ABSTAIN</b>：脚本多写一个字母（{@code "askk"}）时，静默按「无异议」
 * 处理会让一道本该有人看的调用直接放行，而调用者完全不知道自己的拼写错了。抛出的异常会被
 * {@code PermissionManager.intercept} 记 WARN 并按无异议处理，因此降级行为不变，但错误是可见的。
 * <p>
 * <b>处理器抛错按「无异议」处理</b>：处理器报错（含脚本超时）时 {@code PermissionManager.intercept}
 * 只记 WARN 并视为没有意见，与 Java 插件拦截器完全同权。这是刻意的 fail-open 取舍——
 * 一个插件的故障不该让整条工具调用链崩掉；真正的把关仍在核心策略与 PLAN 白名单上。
 * <p>
 * 内核在核心策略已经拒绝时<b>根本不会调用</b>本扩展点（结果不可能更宽），因此脚本不必处理这种情况。
 *
 * @author zcd
 */
public final class PermissionCodec implements ExtensionCodec<PermissionCheckRequest, PermissionVerdict> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "permission";

    /** 结果载荷里的裁定字段名。 */
    private static final String FIELD_VERDICT = "verdict";

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
    public PermissionVerdict decodeResult(JsonNode result, String routeKey) {
        String verdict = Payloads.text(result, FIELD_VERDICT);
        if (verdict == null) {
            // 缺字段按「无异议」：脚本可以不表态，这是常态而非错误
            return PermissionVerdict.abstain();
        }
        String reason = Payloads.text(result, FIELD_REASON);
        switch (verdict.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ABSTAIN":
                return PermissionVerdict.abstain();
            case "ASK":
                return PermissionVerdict.ask(reason);
            case "DENY":
                return PermissionVerdict.deny(reason);
            default:
                throw new JellyfishException("权限拦截裁定非法: " + verdict + "（tool=" + routeKey + "）");
        }
    }
}
