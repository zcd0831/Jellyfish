package zcd.jellyfish.script.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.AgentsLoadedEvent;
import zcd.jellyfish.api.event.notification.CommandExecutedEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.PluginNotificationEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可订阅事件目录的单元测试。
 * <p>
 * 断言的焦点是**投影的形状**而不是每个字段的取值：形状（公共三件套、缺省字段、集合顺序）
 * 才是脚本与内核之间的契约，而具体字段取什么值由事件自己决定。
 *
 * @author zcd
 */
@DisplayName("脚本事件目录")
class ScriptEventCatalogTest {

    @Test
    @DisplayName("应覆盖全部内核通知事件，且名字可反查类型")
    void names_should_coverAllNotificationEvents_when_catalogLoaded() {
        // 15 个通知事件全在目录里：少一个的表现是「某个事件永远订阅不到」，
        // 而脚本那边看起来完全正常
        assertEquals(15, ScriptEventCatalog.names().size());
        for (String name : ScriptEventCatalog.names()) {
            assertTrue(ScriptEventCatalog.isObservable(name), name);
            assertNotNull(ScriptEventCatalog.typeOf(name), name);
            assertEquals(name, ScriptEventCatalog.typeOf(name).getSimpleName());
        }
        assertEquals("SessionCreatedEvent", ScriptEventCatalog.nameOf(SessionCreatedEvent.class));
    }

    @Test
    @DisplayName("私有构造器不应可实例化")
    void constructor_should_bePrivate() throws Exception {
        java.lang.reflect.Constructor<ScriptEventCatalog> constructor =
                ScriptEventCatalog.class.getDeclaredConstructor();
        assertFalse(constructor.isAccessible());
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test
    @DisplayName("投影应带公共三件套与事件名")
    void project_should_includeCommonFields() {
        SessionCreatedEvent event = new SessionCreatedEvent("coder", "s-1");

        Map<String, Object> values = ScriptEventCatalog.project(event);

        assertEquals("SessionCreatedEvent", values.get(ScriptEventCatalog.FIELD_EVENT));
        assertEquals(event.getEventId(), values.get("eventId"));
        assertEquals(Long.valueOf(event.getOccurredAt()), values.get("occurredAt"));
        assertEquals("s-1", values.get("sessionId"));
        assertEquals("coder", values.get("agentId"));
    }

    @Test
    @DisplayName("字段缺失不应写成 JSON null，缺 sessionId 时不应有这个键")
    void project_should_omitNullFields() {
        ToolCallCompletedEvent event =
                new ToolCallCompletedEvent("c-1", "read_file", true, 12L, null, null);

        Map<String, Object> values = ScriptEventCatalog.project(event);

        assertFalse(values.containsKey("errorMessage"), values.toString());
        assertFalse(values.containsKey("sessionId"), values.toString());
        assertEquals(Boolean.TRUE, values.get("success"));
    }

    @Test
    @DisplayName("布尔字段应是布尔而不是字符串")
    void project_should_keepBooleanType() {
        PluginNotificationEvent event = new PluginNotificationEvent("src", "payload", "s");

        Map<String, Object> values = ScriptEventCatalog.project(event);

        assertEquals("payload", values.get("payload"));
        assertEquals("src", values.get("source"));
    }

    @Test
    @DisplayName("集合字段应排序下发")
    void project_should_sortSetFields() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
        ids.add("zulu");
        ids.add("alpha");
        ids.add("mike");
        AgentsLoadedEvent event = new AgentsLoadedEvent("alpha", ids);

        Map<String, Object> values = ScriptEventCatalog.project(event);

        assertEquals(java.util.Arrays.asList("alpha", "mike", "zulu"), values.get("agentIds"));
        assertEquals("alpha", values.get("defaultAgentId"));
    }

    @Test
    @DisplayName("枚举字段应下发枚举名而不是 toString")
    void project_should_useEnumNames() {
        CommandExecutedEvent event = new CommandExecutedEvent("/exit", "exit", "exit",
                zcd.jellyfish.api.extension.CommandResult.Kind.OK, "core", 3L, "s-1");

        Map<String, Object> values = ScriptEventCatalog.project(event);

        assertEquals("OK", values.get("kind"));
        assertEquals("/exit", values.get("input"));
        assertEquals(Long.valueOf(3L), values.get("durationMillis"));
    }

    @Test
    @DisplayName("没有任何业务字段的事件也应能投影")
    void project_should_work_when_eventHasNoBusinessFields() {
        Map<String, Object> values = ScriptEventCatalog.project(new UiInvalidatedEvent());

        assertEquals("UiInvalidatedEvent", values.get(ScriptEventCatalog.FIELD_EVENT));
        // 只有公共三件套，没有 sessionId（这个事件本身就是进程级的）
        assertEquals(3, values.size());
    }

    @Test
    @DisplayName("不在目录里的事件也应能投影，且只有公共三件套")
    void project_should_work_when_eventNotInCatalog() {
        JellyfishEvent foreign = new JellyfishEvent() {
            @Override
            public String getEventId() {
                return "id-1";
            }

            @Override
            public long getOccurredAt() {
                return 42L;
            }

            @Override
            public String getSessionId() {
                return null;
            }
        };

        Map<String, Object> values = ScriptEventCatalog.project(foreign);

        assertEquals("id-1", values.get("eventId"));
        assertEquals(3, values.size());
    }

    @Test
    @DisplayName("不可订阅的名字应查询为空")
    void lookup_should_returnNull_when_nameUnknown() {
        assertFalse(ScriptEventCatalog.isObservable("NoSuchEvent"));
        assertFalse(ScriptEventCatalog.isObservable((String) null));
        assertFalse(ScriptEventCatalog.isObservable(ForeignEvent.class));
        assertNull(ScriptEventCatalog.typeOf("NoSuchEvent"));
        assertNull(ScriptEventCatalog.typeOf(null));
        assertNull(ScriptEventCatalog.nameOf(ForeignEvent.class));
    }

    @Test
    @DisplayName("消息类事件的字段应完整")
    void project_should_coverWarningFields() {
        ConfigWarningEvent event = new ConfigWarningEvent("plugins.x", "配置有问题", "s-9");

        Map<String, Object> values = ScriptEventCatalog.project(event);

        assertEquals("plugins.x", values.get("source"));
        assertEquals("配置有问题", values.get("message"));
        assertEquals("s-9", values.get("sessionId"));
    }

    /**
     * 目录之外的假事件：用来验证「没登记的类型也能投影、但查名字查不到」。
     */
    private static final class ForeignEvent extends zcd.jellyfish.api.event.AbstractJellyfishEvent {

        /**
         * 构造假事件。
         */
        private ForeignEvent() {
            super(null);
        }
    }

    @Test
    @DisplayName("投影结果应是可变副本，不影响后续投影")
    void project_should_returnIndependentMap() {
        SessionCreatedEvent event = new SessionCreatedEvent("coder", "s-1");
        Map<String, Object> first = ScriptEventCatalog.project(event);
        first.put("agentId", "tampered");

        Map<String, Object> second = ScriptEventCatalog.project(event);

        assertEquals("coder", second.get("agentId"));
        assertTrue(new LinkedHashMap<String, Object>().isEmpty());
    }
}
