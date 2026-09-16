package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.SessionDeleteRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话删除编解码：{@code SessionDeleteRequest} ↔ 无结果。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1"}
 *   result  : 省略
 * </pre>
 * <b>它是插件清理自己那份会话状态的唯一时机</b>：插件拿不到会话，也没法在别处知道会话什么时候没了，
 * 因此按 {@code sessionId} 归属的状态（待办、索引、缓存……）必须在这里销毁。不处理的话，
 * 每删一个会话就多留一份孤儿数据——而删除会话这件事本身就是为了不再堆积。
 * <p>
 * <b>失败不影响其它插件</b>：{@code SessionManager} 逐个调用并隔离异常，因此脚本超时或报错
 * 只会让这一份数据删不掉（记 WARN），不会让「删除会话」这个动作失败。
 *
 * @author zcd
 */
public final class SessionDeleteCodec implements ExtensionCodec<SessionDeleteRequest, Void> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "session_delete";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<SessionDeleteRequest> requestType() {
        return SessionDeleteRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 多个插件各删自己那份：内核用 bindings(SessionDeleteRequest.class, null) 依次调用
        return true;
    }

    @Override
    public JsonNode encodeRequest(SessionDeleteRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public Void decodeResult(JsonNode result, String routeKey) {
        // 无结果：请求类型即 Void，处理器返回 null 是与 Java 插件一致的做法
        return null;
    }
}
