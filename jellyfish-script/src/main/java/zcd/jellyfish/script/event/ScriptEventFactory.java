package zcd.jellyfish.script.event;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.PluginNotificationEvent;
import zcd.jellyfish.script.ScriptJson;

/**
 * 脚本可发布事件的构造器，同时是可发布白名单。
 * <p>
 * 白名单只有两类，而且是刻意的窄：脚本能造的只有「一条通知」和「一条用户可见的告警」。
 * <ul>
 *   <li><b>不允许脚本伪造内核语义事件</b>：{@code ToolCallCompletedEvent}、{@code PermissionDecidedEvent}
 *       这类事件是内核事实的转述，指标、审计与界面都按「它是真的」来消费。脚本能造它们，
 *       等于给自己开了一条往审计里写假账的路——而这条路一旦存在，所有事件的可信度都降级为
 *       「取决于最有恶意的插件」。</li>
 *   <li><b>两种载荷都是自由的</b>：通知的 {@code payload} 任意 JSON，告警只有一句文本。
 *       因此这里不需要跟随内核事件的字段演进，白名单不会因为内核加字段而需要改。</li>
 * </ul>
 * 拒绝一律返回原因字符串（而不是抛异常）：调用点是协议消息解析，
 * 拒绝是**正常结论**——脚本写错事件名不该让网关侧的任何东西崩掉。
 *
 * @author zcd
 */
public final class ScriptEventFactory {

    /** 通用通知事件名。 */
    public static final String PLUGIN_NOTIFICATION = "PluginNotificationEvent";

    /** 配置告警事件名。 */
    public static final String CONFIG_WARNING = "ConfigWarningEvent";

    /** 可发布事件名集合。 */
    public static final Set<String> EMITTABLE;

    static {
        Set<String> names = new LinkedHashSet<String>();
        names.add(PLUGIN_NOTIFICATION);
        names.add(CONFIG_WARNING);
        EMITTABLE = Collections.unmodifiableSet(names);
    }

    /**
     * 私有构造器：纯静态工具。
     */
    private ScriptEventFactory() {
    }

    /**
     * 构造脚本请求发布的事件。
     *
     * @param scriptId  发起脚本的标识，用作缺省 source
     * @param eventName 事件名（类名）
     * @param payload   载荷：业务字段 + 可选的 {@code sessionId}
     * @return 事件对象
     * @throws JellyfishException 事件名不在白名单内，或必填字段缺失时抛出
     */
    public static JellyfishEvent build(String scriptId, String eventName, JsonNode payload) {
        JsonNode body = payload == null || payload.isNull() ? null : payload;
        String sessionId = text(body, "sessionId");
        String source = text(body, "source");
        if (source == null) {
            source = "script:" + scriptId;
        }
        if (PLUGIN_NOTIFICATION.equals(eventName)) {
            Object value = body == null || !body.has("payload") ? null : unwrap(body.get("payload"));
            return new PluginNotificationEvent(source, value, sessionId);
        }
        if (CONFIG_WARNING.equals(eventName)) {
            String message = text(body, "message");
            if (message == null || message.trim().isEmpty()) {
                throw new JellyfishException("ConfigWarningEvent 需要非空 message");
            }
            return new ConfigWarningEvent(source, message, sessionId);
        }
        throw new JellyfishException("事件 " + eventName + " 不可由脚本发布，可发布的有: " + EMITTABLE);
    }

    /**
     * 取字符串字段。
     *
     * @param body 载荷，可为 {@code null}
     * @param name 字段名
     * @return 字段值；缺失、{@code null} 或是空串时返回 {@code null}
     */
    private static String text(JsonNode body, String name) {
        if (body == null || !body.has(name) || body.get(name).isNull()) {
            return null;
        }
        String value = body.get(name).asText();
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * 把 JSON 节点还原成普通 Java 值。
     * <p>
     * 直接存 {@code JsonNode} 会让事件里带着一个 Jackson 类型：事件是 api 侧的契约值类型，
     * 而 api 刻意不依赖 Jackson，下游（指标、事件桥接、测试断言）也就没法指望拿到的是普通值。
     *
     * @param node 节点
     * @return 普通 Java 值（对象 → {@code Map}，数组 → {@code List}，标量 → 包装类型）
     */
    private static Object unwrap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return ScriptJson.treeToValue(node, new TypeReference<Object>() { });
    }
}
