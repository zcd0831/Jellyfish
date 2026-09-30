package zcd.jellyfish.api.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginAction} 的单元测试：静态工厂的取值、拒绝与种类。
 * <p>
 * 这些断言守的是「动作清单有界」这件事：插件能构造出来的只有这里列出的几种，
 * 而每种只带它自己需要的那几个字段。
 *
 * @author zcd
 */
@DisplayName("PluginAction 插件动作")
class PluginActionTest {

    @Test
    void sendUserMessage_should_carry_text_and_delivery() {
        PluginAction action = PluginAction.sendUserMessage("s1", "接着把测试补了", DeliverAs.FOLLOW_UP);

        assertEquals(PluginAction.Kind.SEND_USER_MESSAGE, action.getKind());
        assertEquals("s1", action.getSessionId());
        assertEquals("接着把测试补了", ((PluginAction.SendUserMessage) action).getText());
        assertEquals(DeliverAs.FOLLOW_UP, ((PluginAction.SendUserMessage) action).getDeliverAs());
    }

    @Test
    void sendUserMessage_should_keep_text_verbatim() {
        // 首尾空白是用户内容的一部分：内核不做「顺手修剪」，否则插件无法表达格式化过的文本
        PluginAction action = PluginAction.sendUserMessage("s1", "  缩进的两行\n", DeliverAs.STEER);

        assertEquals("  缩进的两行\n", ((PluginAction.SendUserMessage) action).getText());
    }

    @Test
    void sendUserMessage_should_reject_blank_text() {
        // 一条空消息在会话里会变成需要模型解释的东西，而不是一次静默的无操作
        assertThrows(JellyfishException.class,
                () -> PluginAction.sendUserMessage("s1", "   ", DeliverAs.STEER));
    }

    @Test
    void sendUserMessage_should_reject_blank_session() {
        assertThrows(JellyfishException.class,
                () -> PluginAction.sendUserMessage("  ", "你好", DeliverAs.STEER));
    }

    @Test
    void sendUserMessage_should_reject_null_delivery() {
        assertThrows(NullPointerException.class,
                () -> PluginAction.sendUserMessage("s1", "你好", null));
    }

    @Test
    void compact_should_carry_only_session() {
        // 只表达「现在就压」，不表达「怎么压」：压缩策略已经有一条自己的路（CompactionStrategy）
        PluginAction action = PluginAction.compact("s1");

        assertEquals(PluginAction.Kind.COMPACT, action.getKind());
        assertEquals("s1", action.getSessionId());
    }

    @Test
    void abortTurn_should_carry_only_session() {
        PluginAction action = PluginAction.abortTurn("s1");

        assertEquals(PluginAction.Kind.ABORT_TURN, action.getKind());
    }

    @Test
    void switchModel_should_allow_null_provider_and_model() {
        // null 表示「跟随配置默认」，与 /model 的语义一致
        PluginAction.SwitchModel action =
                (PluginAction.SwitchModel) PluginAction.switchModel("s1", null, null);

        assertEquals(PluginAction.Kind.SWITCH_MODEL, action.getKind());
        assertNull(action.getProvider());
        assertNull(action.getModel());
    }

    @Test
    void switchModel_should_carry_provider_and_model() {
        PluginAction.SwitchModel action =
                (PluginAction.SwitchModel) PluginAction.switchModel("s1", "openai", "gpt-4o");

        assertEquals("openai", action.getProvider());
        assertEquals("gpt-4o", action.getModel());
    }

    @Test
    void forkSession_should_carry_branch_point_and_title() {
        PluginAction.ForkSession action =
                (PluginAction.ForkSession) PluginAction.forkSession("s1", "m7", "换条路走");

        assertEquals(PluginAction.Kind.FORK_SESSION, action.getKind());
        assertEquals("m7", action.getMessageId());
        assertEquals("换条路走", action.getTitle());
    }

    @Test
    void rebuildToolCatalog_should_carry_reason() {
        PluginAction.RebuildToolCatalog action =
                (PluginAction.RebuildToolCatalog) PluginAction.rebuildToolCatalog("s1", "插件热部署了");

        assertEquals(PluginAction.Kind.REBUILD_TOOL_CATALOG, action.getKind());
        assertEquals("插件热部署了", action.getReason());
    }

    @Test
    void toString_should_not_leak_payload() {
        // 日志与诊断里只该出现会话标识，不该把用户内容或凭据顺手打出去
        String text = PluginAction.sendUserMessage("s1", "密码是 hunter2", DeliverAs.STEER).toString();

        assertTrue(text.contains("s1"), text);
        assertTrue(!text.contains("hunter2"), text);
    }

    @Test
    void kind_should_cover_every_factory() {
        // 静态工厂与种类一一对应：清单「有界且逐条列明」这句话因此可以被程序检查
        assertEquals(6, PluginAction.Kind.values().length);
    }
}
