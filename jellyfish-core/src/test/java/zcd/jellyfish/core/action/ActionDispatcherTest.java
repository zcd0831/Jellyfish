package zcd.jellyfish.core.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zcd.jellyfish.api.action.ActionFailureReason;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.action.ActionStatus;
import zcd.jellyfish.api.action.DeliverAs;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.prompt.ToolCatalog;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActionDispatcher} 的单元测试：把排出来的动作翻译成会话域操作，以及失败语义。
 *
 * @author zcd
 */
@DisplayName("ActionDispatcher 动作执行体")
class ActionDispatcherTest {

    /** 真实队列：本类验证的正是「取出来之后做了什么」。 */
    private ActionQueue queue;

    /** 会话域服务：桩，验证写入内容。 */
    private SessionManager sessionManager;

    /** 压缩器：桩，验证走的是与 /compact 同一条路径。 */
    private ConversationCompactor compactor;

    /** 工具目录。 */
    private ToolCatalog toolCatalog;

    /** 被测对象。 */
    private ActionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        queue = new ActionQueue();
        sessionManager = mock(SessionManager.class);
        compactor = mock(ConversationCompactor.class);
        toolCatalog = mock(ToolCatalog.class);
        dispatcher = new ActionDispatcher(queue, sessionManager, compactor, toolCatalog);
        dispatcher.beginTurn("s1");
    }

    @Test
    void drainTurnBoundary_should_append_user_message_when_steer() {
        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "顺便把日志也改了", DeliverAs.STEER));

        assertEquals(1, dispatcher.drainTurnBoundary("s1", true));

        ArgumentCaptor<LlmMessage> message = ArgumentCaptor.forClass(LlmMessage.class);
        verify(sessionManager).appendMessage(eq("s1"), message.capture(), eq(null));
        assertEquals("顺便把日志也改了", message.getValue().getContent());
        assertEquals(ActionStatus.DONE, handle.getStatus());
    }

    @Test
    void drainTurnBoundary_should_fail_message_when_no_remaining_rounds() {
        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "太晚了", DeliverAs.STEER));

        assertEquals(0, dispatcher.drainTurnBoundary("s1", false));

        // 投了也永远不会发给模型，只会在历史里留下一条没人回答的提问
        verify(sessionManager, never()).appendMessage(any(), any(), any());
        assertEquals(ActionStatus.FAILED, handle.getStatus());
        assertEquals(ActionFailureReason.NO_REMAINING_ROUNDS, handle.getFailureReason());
        assertTrue(handle.getResult().contains("轮次已用尽"), handle.getResult());
    }

    @Test
    void drainConvergence_should_report_injected_when_follow_up() {
        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "接着干这一摊", DeliverAs.FOLLOW_UP));

        boolean injected = dispatcher.drainConvergence("s1", true);

        // 返回值就是「别收敛」的信号：调用方据此让本回合多跑一轮，而不是开一个新回合
        assertTrue(injected);
        assertEquals(ActionStatus.DONE, handle.getStatus());
    }

    @Test
    void drainConvergence_should_return_false_when_nothing_queued() {
        assertFalse(dispatcher.drainConvergence("s1", true));
    }

    @Test
    void drainTurnBoundary_should_start_compaction_when_available() {
        when(compactor.isAvailable()).thenReturn(true);
        ActionHandle handle = queue.submit("plugin-a", PluginAction.compact("s1"));

        dispatcher.drainTurnBoundary("s1", true);

        // 与用户敲 /compact 同一个入口：外壳不需要为插件发起的压缩新增任何展示
        verify(compactor).start("s1", CompactionTrigger.MANUAL);
        assertEquals(ActionStatus.DONE, handle.getStatus());
    }

    @Test
    void drainTurnBoundary_should_fail_when_compaction_unavailable() {
        when(compactor.isAvailable()).thenReturn(false);
        ActionHandle handle = queue.submit("plugin-a", PluginAction.compact("s1"));

        dispatcher.drainTurnBoundary("s1", true);

        verify(compactor, never()).start(any(), any());
        assertEquals(ActionStatus.FAILED, handle.getStatus());
        assertEquals(ActionFailureReason.COMPACTION_UNAVAILABLE, handle.getFailureReason());
        assertTrue(handle.getResult().contains("压缩不可用"), handle.getResult());
    }

    @Test
    void drainTurnBoundary_should_switch_session_model() {
        ActionHandle handle = queue.submit("plugin-a", PluginAction.switchModel("s1", "openai", "gpt-4o"));

        dispatcher.drainTurnBoundary("s1", true);

        verify(sessionManager).switchModel("s1", "openai", "gpt-4o");
        assertEquals(ActionStatus.DONE, handle.getStatus());
    }

    @Test
    void drainTurnBoundary_should_keep_going_when_one_action_throws() {
        // 一条坏建议不该把整个排空点弄失败，后面的动作照跑
        when(sessionManager.switchModel(any(), any(), any())).thenThrow(new IllegalStateException("模型不认识"));
        ActionHandle broken = queue.submit("plugin-a", PluginAction.switchModel("s1", "x", "y"));
        ActionHandle healthy = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "我没事", DeliverAs.STEER));

        dispatcher.drainTurnBoundary("s1", true);

        // 一条坏建议不该把整个排空点弄失败，后面的动作照跑
        assertEquals(ActionStatus.FAILED, broken.getStatus());
        assertEquals(ActionFailureReason.EXECUTION_ERROR, broken.getFailureReason());
        assertTrue(broken.getResult().contains("模型不认识"), broken.getResult());
        assertEquals(ActionStatus.DONE, healthy.getStatus());
    }

    @Test
    void drainTurnBoundary_should_rebuild_tool_catalog() {
        when(toolCatalog.rebuild("s1")).thenReturn(true);
        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.rebuildToolCatalog("s1", "MCP 工具变了"));

        dispatcher.drainTurnBoundary("s1", true);

        // 重建必定换来一次缓存前缀断裂，因此结果里要说清楚「下一个回合生效」
        assertEquals(ActionStatus.DONE, handle.getStatus());
        assertTrue(handle.getResult().contains("下一个回合"), handle.getResult());
    }

    @Test
    void drainTurnBoundary_should_succeed_when_nothing_frozen_yet() {
        // 没有冻结清单 = 本会话还没装配过请求，下一个回合本就会带上最新工具集，没有代价
        when(toolCatalog.rebuild("s1")).thenReturn(false);
        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.rebuildToolCatalog("s1", "MCP 工具变了"));

        dispatcher.drainTurnBoundary("s1", true);

        assertEquals(ActionStatus.DONE, handle.getStatus());
        assertTrue(handle.getResult().contains("尚未冻结"), handle.getResult());
    }

    @Test
    void drainTurnBoundary_should_fail_when_rebuild_throws() {
        when(toolCatalog.rebuild("s1")).thenThrow(new IllegalStateException("目录炸了"));
        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.rebuildToolCatalog("s1", "MCP 工具变了"));

        dispatcher.drainTurnBoundary("s1", true);

        assertEquals(ActionStatus.FAILED, handle.getStatus());
        assertEquals(ActionFailureReason.EXECUTION_ERROR, handle.getFailureReason());
    }

    @Test
    void drainTurnBoundary_should_fork_session() {
        Session forked = mock(Session.class);
        when(forked.getSessionId()).thenReturn("s2");
        when(sessionManager.fork("s1", "m7", "分支")).thenReturn(forked);
        ActionHandle handle = queue.submit("plugin-a", PluginAction.forkSession("s1", "m7", "分支"));

        dispatcher.drainTurnBoundary("s1", true);

        // 新会话 id 写在结果里：不为插件把当前会话切过去，那是外壳的主权
        assertEquals(ActionStatus.DONE, handle.getStatus());
        assertTrue(handle.getResult().contains("s2"), handle.getResult());
    }

    @Test
    void drainTurnBoundary_should_do_nothing_when_no_window() {
        // 回合外的排空点是空转：不是错误，也不需要任何日志
        dispatcher.endTurn("s1");

        assertEquals(0, dispatcher.drainTurnBoundary("s1", true));
        assertFalse(dispatcher.drainConvergence("s1", true));
    }
}
