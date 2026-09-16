package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话持久化编解码：{@code SessionPersistRequest} ↔ 无结果。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"snapshot": { ...SessionSnapshot... }}
 *   result  : 省略
 * </pre>
 * <b>快照直接复用 api 的值类型序列化</b>，不另造一套 JSON：{@code SessionSnapshot} 及其嵌套类型
 * 本身就是「恰好一个可见构造器 + {@code -parameters}」的跨边界值类型，
 * 会话持久化插件（Java）用的也是同一套形状，因此脚本读到的字段名与 Java 插件看到的完全一致。
 * <p>
 * ⚠️ <b>这是不可丢路径</b>：{@code SessionManager} 的每个变更入口都同步派发本请求，处理器抛出的异常
 * <b>原样上抛</b>——创建会话时先落盘再入表，所以一个卡住的脚本会让每次建会话都阻塞一个超时周期然后失败。
 * 这与 {@code session-file}（Java 插件）的风险完全同级，不是脚本引入的新特权，但由于脚本还会超时，
 * 它的体感更差，因此运维文档里必须写明这条。
 *
 * @author zcd
 */
public final class SessionPersistCodec implements ExtensionCodec<SessionPersistRequest, Void> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "session_persist";

    /** 快照字段名。 */
    private static final String FIELD_SNAPSHOT = "snapshot";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<SessionPersistRequest> requestType() {
        return SessionPersistRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 多个插件各存一份：内核用 bindings(SessionPersistRequest.class, null) 依次调用
        return true;
    }

    @Override
    public JsonNode encodeRequest(SessionPersistRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SNAPSHOT, request.getSnapshot());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public Void decodeResult(JsonNode result, String routeKey) {
        // 无结果：请求类型即 Void，处理器返回 null 是与 Java 插件一致的做法
        return null;
    }
}
