package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.script.ScriptJson;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话恢复编解码：{@code SessionRestoreRequest} ↔ {@code SessionRestoreResult}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {}
 *   result  : {"sessions": [ { ...SessionSnapshot... } ]}     // 空数组 = 本插件恢复不出会话
 * </pre>
 * <b>恢复的失败语义与持久化相反</b>：单个插件读不出只告警跳过，不影响其它插件，也不影响内核启动。
 * 因此这里对形状异常一律降级为空结果，而不是抛错——一个坏掉的会话文件不该让整个恢复流程失败。
 * <p>
 * <b>调用时机在 {@code pluginManager.bootstrap()} 之后</b>，而脚本运行时是懒启动的，
 * 所以「第一次恢复」往往会触发 worker 拉起。这是正常时序，不是异常：启动期零进程的代价就是
 * 首次真正用到时付一次 fork 成本。
 *
 * @author zcd
 */
public final class SessionRestoreCodec implements ExtensionCodec<SessionRestoreRequest, SessionRestoreResult> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "session_restore";

    /** 会话数组字段名。 */
    private static final String FIELD_SESSIONS = "sessions";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<SessionRestoreRequest> requestType() {
        return SessionRestoreRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 多个插件各恢复自己那份：内核用 bindings(SessionRestoreRequest.class, null) 依次问
        return true;
    }

    @Override
    public JsonNode encodeRequest(SessionRestoreRequest request) {
        // 恢复是「把你手上的会话都交出来」，请求本身没有输入，因此载荷是空对象而不是 null：
        // 空对象能让脚本侧统一按「读字段」的姿势写，不必分辨 params 存不存在
        return ScriptJson.objectNode();
    }

    @Override
    public SessionRestoreResult decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return SessionRestoreResult.empty();
        }
        List<SessionSnapshot> sessions = new ArrayList<SessionSnapshot>();
        JsonNode node = result.get(FIELD_SESSIONS);
        if (node == null || !node.isArray()) {
            return SessionRestoreResult.empty();
        }
        for (JsonNode element : node) {
            if (element == null || !element.isObject()) {
                continue;
            }
            try {
                sessions.add(ScriptJson.treeToValue(element, SessionSnapshot.class));
            } catch (RuntimeException e) {
                // 单个快照读不出来就跳过：恢复阶段的既有语义就是「坏的那份丢下，别拖累其它插件」
                continue;
            }
        }
        return SessionRestoreResult.of(sessions);
    }
}
