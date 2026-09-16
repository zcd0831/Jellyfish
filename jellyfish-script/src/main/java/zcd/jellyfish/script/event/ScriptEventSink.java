package zcd.jellyfish.script.event;

import com.fasterxml.jackson.databind.JsonNode;

import zcd.jellyfish.api.JellyfishException;

/**
 * 脚本发布事件的受理方：由事件桥接实现，网关只负责把请求转过来。
 * <p>
 * 网关（协议/进程侧）刻意不认识事件语义：它既不知道哪些事件可发布，也不该知道
 * 「PluginNotificationEvent 需要什么字段」——那是白名单的事，而白名单属于
 * {@link ScriptEventFactory} 一处。
 *
 * @author zcd
 */
@FunctionalInterface
public interface ScriptEventSink {

    /**
     * 受理一次发布请求。
     *
     * @param scriptId  发起脚本
     * @param eventName 事件名
     * @param payload   载荷
     * @return 已发布事件的事件标识（网关用它掐掉回声）
     * @throws JellyfishException 事件不可发布或必填字段缺失时抛出，原因原样回给脚本侧
     */
    String accept(String scriptId, String eventName, JsonNode payload);
}
