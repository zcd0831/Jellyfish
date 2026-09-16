package zcd.jellyfish.script.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.PluginNotificationEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.script.ScriptJson;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可发布事件构造器的单元测试。
 * <p>
 * 重点在**拒绝**而不是受理：受理只有两种形态，而拒绝的每一条都对应一种「脚本能伪造内核事实」
 * 的路径，漏掉一条就是审计可被写入假数据。
 *
 * @author zcd
 */
@DisplayName("脚本事件工厂")
class ScriptEventFactoryTest {

    @Test
    @DisplayName("白名单应只有两类自由载荷事件")
    void emittable_should_beNarrow_when_inspected() {
        assertEquals(2, ScriptEventFactory.EMITTABLE.size());
        assertTrue(ScriptEventFactory.EMITTABLE.contains("PluginNotificationEvent"));
        assertTrue(ScriptEventFactory.EMITTABLE.contains("ConfigWarningEvent"));
    }

    @Test
    @DisplayName("通知事件应带上脚本来源、载荷与会话")
    void build_should_createNotification_when_payloadGiven() {
        JellyfishEvent event = ScriptEventFactory.build("jira", "PluginNotificationEvent",
                ScriptJson.tree("{\"source\":\"jira-hook\",\"payload\":{\"n\":1},\"sessionId\":\"s-1\"}"));

        PluginNotificationEvent notification = (PluginNotificationEvent) event;
        assertEquals("jira-hook", notification.getSource());
        assertEquals("s-1", notification.getSessionId());
        assertTrue(notification.getPayload().toString().contains("n=1"), notification.getPayload().toString());
    }

    @Test
    @DisplayName("缺省来源应是脚本标识，载荷可为空")
    void build_should_defaultSourceAndPayload_when_absent() {
        JellyfishEvent event = ScriptEventFactory.build("jira", "PluginNotificationEvent", null);

        PluginNotificationEvent notification = (PluginNotificationEvent) event;
        assertEquals("script:jira", notification.getSource());
        assertNull(notification.getPayload());
        assertNull(notification.getSessionId());
    }

    @Test
    @DisplayName("载荷应还原成普通 Java 值而不是 Jackson 节点")
    void build_should_unwrapPayload_when_objectGiven() {
        JellyfishEvent event = ScriptEventFactory.build("jira", "PluginNotificationEvent",
                ScriptJson.tree("{\"payload\":{\"items\":[1,2]}}"));

        Object payload = ((PluginNotificationEvent) event).getPayload();
        assertTrue(payload instanceof java.util.Map, String.valueOf(payload));
        assertEquals(Arrays.asList(1, 2), ((java.util.Map<?, ?>) payload).get("items"));
    }

    @Test
    @DisplayName("告警事件应有 source 与 message")
    void build_should_createWarning_when_messageGiven() {
        JellyfishEvent event = ScriptEventFactory.build("jira", "ConfigWarningEvent",
                ScriptJson.tree("{\"message\":\"配额快满了\",\"sessionId\":\"s-2\"}"));

        ConfigWarningEvent warning = (ConfigWarningEvent) event;
        assertEquals("script:jira", warning.getSource());
        assertEquals("配额快满了", warning.getMessage());
        assertEquals("s-2", warning.getSessionId());
    }

    @Test
    @DisplayName("告警缺 message 或 message 是空白时应拒绝")
    void build_should_reject_when_warningMessageMissing() {
        assertThrows(JellyfishException.class,
                () -> ScriptEventFactory.build("jira", "ConfigWarningEvent", null));
        assertThrows(JellyfishException.class,
                () -> ScriptEventFactory.build("jira", "ConfigWarningEvent",
                        ScriptJson.tree("{\"message\":\"   \"}")));
    }

    @Test
    @DisplayName("内核语义事件不可发布")
    void build_should_reject_when_eventIsKernelSemantic() {
        // 这是本白名单存在的原因：脚本能造「会话已创建」就等于能往审计里写假账
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> ScriptEventFactory.build("jira", "SessionCreatedEvent",
                        ScriptJson.tree("{\"agentId\":\"fake\"}")));

        assertTrue(error.getMessage().contains("不可由脚本发布"), error.getMessage());
        assertTrue(error.getMessage().contains("PluginNotificationEvent"), error.getMessage());
    }

    @Test
    @DisplayName("不存在的事件名应拒绝")
    void build_should_reject_when_eventUnknown() {
        assertThrows(JellyfishException.class,
                () -> ScriptEventFactory.build("jira", "NoSuchEvent", null));
        assertThrows(JellyfishException.class,
                () -> ScriptEventFactory.build("jira", null, null));
    }

    @Test
    @DisplayName("构造出来的事件应是目录里可观察的类型")
    void build_should_produceObservableEvent() {
        JellyfishEvent event = ScriptEventFactory.build("jira", "ConfigWarningEvent",
                ScriptJson.tree("{\"message\":\"x\"}"));

        assertNotNull(event.getEventId());
        assertTrue(ScriptEventCatalog.isObservable(event.getClass()));
    }

    @Test
    @DisplayName("空白 source 应退回脚本标识")
    void build_should_defaultSource_when_blank() {
        JellyfishEvent event = ScriptEventFactory.build("jira", "ConfigWarningEvent",
                ScriptJson.tree("{\"message\":\"x\",\"source\":\"\"}"));

        assertEquals("script:jira", ((ConfigWarningEvent) event).getSource());
    }

    @Test
    @DisplayName("不可发布的事件即使载荷正确也应拒绝")
    void build_should_rejectSemanticEvent_evenWhen_payloadLooksValid() {
        assertThrows(JellyfishException.class, () -> ScriptEventFactory.build("jira",
                SessionCreatedEvent.class.getSimpleName(),
                ScriptJson.tree("{\"agentId\":\"coder\",\"sessionId\":\"s-1\"}")));
        assertTrue(ScriptEventFactory.EMITTABLE.containsAll(Collections.singletonList("ConfigWarningEvent")));
    }
}
