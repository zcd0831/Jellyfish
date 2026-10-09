package zcd.jellyfish.infra.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.action.ActionFailureReason;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.action.ActionStatus;
import zcd.jellyfish.api.action.DeliverAs;
import zcd.jellyfish.api.action.PluginAction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActionQueue} 的单元测试：窗口生命周期、容量、取用与归属回收。
 * <p>
 * 本类不需要任何协作者，因此队列的语义可以独立验证——「能不能投」这件事由窗口是否存在决定，
 * 而不是由会话服务回答。
 *
 * @author zcd
 */
@DisplayName("ActionQueue 插件动作队列")
class ActionQueueTest {

    /** 被测队列。 */
    private ActionQueue queue;

    @BeforeEach
    void setUp() {
        queue = new ActionQueue();
    }

    @Test
    void submit_should_fail_when_no_turn_in_flight() {
        // 插件从事件回调或自己的线程投递时最常见的处境：那一刻没有回合在跑
        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "接着把 X 改完", DeliverAs.FOLLOW_UP));

        assertEquals(ActionStatus.FAILED, handle.getStatus());
        assertEquals(ActionFailureReason.NO_TURN_IN_FLIGHT, handle.getFailureReason());
        assertTrue(handle.getResult().contains("没有在途回合"), handle.getResult());
        assertTrue(handle.isFinished());
    }

    @Test
    void submit_should_queue_without_executing_when_turn_in_flight() {
        // 「入队即返回、绝不在提交者栈上执行」是硬规则：提交之后到被取走之前，它一直停在 QUEUED
        queue.beginTurn("s1");

        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "把 X 也改了", DeliverAs.STEER));

        assertEquals(ActionStatus.QUEUED, handle.getStatus());
        assertNull(handle.getFailureReason());
        assertFalse(handle.isFinished());
    }

    @Test
    void takeTurnBoundary_should_take_steer_but_keep_follow_up() {
        queue.beginTurn("s1");
        ActionHandle steer = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "改方向", DeliverAs.STEER));
        ActionHandle followUp = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "接着干", DeliverAs.FOLLOW_UP));

        List<ActionQueue.Pending> taken = queue.takeTurnBoundary("s1");

        assertEquals(1, taken.size());
        assertEquals(ActionStatus.EXECUTING, steer.getStatus());
        assertEquals(ActionStatus.QUEUED, followUp.getStatus());
    }

    @Test
    void takeTurnBoundary_should_take_compact_and_switch_model() {
        // 两者都改缓存前缀，因此只在回合边界做——取用规则必须与 STEER 同一档
        queue.beginTurn("s1");
        queue.submit("plugin-a", PluginAction.compact("s1"));
        queue.submit("plugin-a", PluginAction.switchModel("s1", "openai", "gpt-4o"));

        assertEquals(2, queue.takeTurnBoundary("s1").size());
    }

    @Test
    void takeConvergence_should_take_follow_up() {
        queue.beginTurn("s1");
        queue.submit("plugin-a", PluginAction.sendUserMessage("s1", "接着干", DeliverAs.FOLLOW_UP));

        List<ActionQueue.Pending> taken = queue.takeConvergence("s1");

        assertEquals(1, taken.size());
        assertEquals(PluginAction.Kind.SEND_USER_MESSAGE, taken.get(0).getAction().getKind());
    }

    @Test
    void takeConvergence_should_return_empty_when_no_window() {
        assertTrue(queue.takeConvergence("ghost").isEmpty());
        assertTrue(queue.takeTurnBoundary("ghost").isEmpty());
    }

    @Test
    void takeTurnBoundary_should_preserve_delivery_order() {
        queue.beginTurn("s1");
        queue.submit("plugin-a", PluginAction.sendUserMessage("s1", "第一条", DeliverAs.STEER));
        queue.submit("plugin-b", PluginAction.sendUserMessage("s1", "第二条", DeliverAs.STEER));

        List<ActionQueue.Pending> taken = queue.takeTurnBoundary("s1");

        assertEquals("第一条",
                ((PluginAction.SendUserMessage) taken.get(0).getAction()).getText());
        assertEquals("第二条",
                ((PluginAction.SendUserMessage) taken.get(1).getAction()).getText());
    }

    @Test
    void submit_should_drop_when_queue_full() {
        ActionQueue small = new ActionQueue(2);
        small.beginTurn("s1");
        small.submit("plugin-a", PluginAction.sendUserMessage("s1", "一", DeliverAs.STEER));
        small.submit("plugin-a", PluginAction.sendUserMessage("s1", "二", DeliverAs.STEER));

        ActionHandle overflow = small.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "三", DeliverAs.STEER));

        // 丢而不抛：动作是「建议内核做事」，不是「必须完成的事实」；插件靠句柄知道没投出去
        assertEquals(ActionStatus.DROPPED, overflow.getStatus());
        assertEquals(ActionFailureReason.QUEUE_FULL, overflow.getFailureReason());
        assertTrue(overflow.getResult().contains("队列已满"), overflow.getResult());
        assertEquals(2, small.takeTurnBoundary("s1").size());
    }

    @Test
    void endTurn_should_close_window_and_fail_leftovers() {
        queue.beginTurn("s1");
        ActionHandle leftover = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "太晚了", DeliverAs.FOLLOW_UP));

        queue.endTurn("s1");

        // 失败而不是丢弃：它没被容量或插件停止淘汰，而是「回合结束了，再也不会有人来取」
        assertEquals(ActionStatus.FAILED, leftover.getStatus());
        assertEquals(ActionFailureReason.TURN_ENDED_UNREACHED, leftover.getFailureReason());
        assertTrue(leftover.getResult().contains("回合已结束"), leftover.getResult());
        assertEquals(ActionStatus.FAILED,
                queue.submit("plugin-a", PluginAction.compact("s1")).getStatus());
    }

    @Test
    void beginTurn_should_leaveNewWindowAccepting_when_previousWindowWasClosed() {
        // Given：旧窗口里留一条没排空的动作
        queue.beginTurn("s1");
        ActionHandle stale = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "旧窗口里的", DeliverAs.FOLLOW_UP));

        // When：同一会话开新窗口，旧窗口随之关闭
        queue.beginTurn("s1");

        // Then：旧窗口的残留被标失败
        assertEquals(ActionStatus.FAILED, stale.getStatus());
        assertEquals(ActionFailureReason.TURN_SUPERSEDED, stale.getFailureReason());
        // Then：新窗口照常接受动作——「已关闭」属于那一个窗口，不属于这张表。
        // 把关闭状态实现成队列级的话，这里会得到 FAILED，于是同一会话的第二个回合永远投不进动作
        ActionHandle fresh = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "新窗口里的", DeliverAs.FOLLOW_UP));
        assertEquals(ActionStatus.QUEUED, fresh.getStatus());
        assertEquals(1, queue.takeConvergence("s1").size());
    }

    @Test
    void endTurn_should_be_noop_when_no_window() {
        queue.endTurn("ghost");
    }

    @Test
    void dropByOwner_should_drop_own_and_child_namespace_actions() {
        queue.beginTurn("s1");
        ActionHandle own = queue.submit("plugin-a", PluginAction.compact("s1"));
        ActionHandle child = queue.submit("plugin-a::jira", PluginAction.compact("s1"));
        ActionHandle other = queue.submit("plugin-ab", PluginAction.compact("s1"));
        ActionHandle unrelated = queue.submit("plugin-b", PluginAction.compact("s1"));

        int dropped = queue.dropByOwner("plugin-a");

        // 前缀匹配必须带分隔符：否则 plugin-a 会把 plugin-ab 的动作一起清掉
        assertEquals(2, dropped);
        assertEquals(ActionStatus.DROPPED, own.getStatus());
        assertEquals(ActionFailureReason.PLUGIN_STOPPED, own.getFailureReason());
        assertEquals(ActionStatus.DROPPED, child.getStatus());
        assertEquals(ActionStatus.QUEUED, other.getStatus());
        assertEquals(ActionStatus.QUEUED, unrelated.getStatus());
    }

    @Test
    void dropByOwner_should_ignore_null_owner() {
        queue.beginTurn("s1");
        queue.submit("plugin-a", PluginAction.compact("s1"));

        assertEquals(0, queue.dropByOwner(null));
    }

    @Test
    void submit_should_queue_fork_when_capability_available() {
        // fork 已随会话分支能力落地：与其他改会话集合的动作一样在回合边界排空
        queue.beginTurn("s1");

        ActionHandle fork = queue.submit("plugin-a", PluginAction.forkSession("s1", "m1", "分支"));

        assertEquals(ActionStatus.QUEUED, fork.getStatus());
        assertEquals(1, queue.takeTurnBoundary("s1").size());
    }

    @Test
    void submit_should_queue_rebuild_tool_catalog_at_turn_boundary() {
        // 它是「回合边界」那一档：正在跑的回合已经拿过清单，中途换掉会让同一个回合里模型看到两套工具
        queue.beginTurn("s1");

        ActionHandle rebuild = queue.submit("plugin-a", PluginAction.rebuildToolCatalog("s1", "插件变了"));

        assertEquals(ActionStatus.QUEUED, rebuild.getStatus());
        assertEquals(1, queue.takeTurnBoundary("s1").size());
        assertTrue(queue.takeConvergence("s1").isEmpty());
    }

    @Test
    void submit_should_fail_when_window_closes_between_lookup_and_offer() {
        // Given：把交错摆出来——「拿到窗口引用」之后、真正入队之前，回合结束了
        queue.beginTurn("s1");
        queue.betweenWindowAndOffer = () -> queue.endTurn("s1");

        // When
        ActionHandle handle = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "晚了一步", DeliverAs.STEER));

        // Then：这是 N-32 的正题——窗口关闭时会取走队列里的动作并标失败，
        // 但看不到「还没进队列」的这一条，因此必须由入队侧兜住
        assertEquals(ActionStatus.FAILED, handle.getStatus());
        assertEquals(ActionFailureReason.TURN_ENDED_UNREACHED, handle.getFailureReason());
        // 它不能停在 QUEUED：插件据此等终态会永远等不到
        assertTrue(handle.isFinished(), "任何路径都不该把动作留在 QUEUED 上无人认领");
    }

    @Test
    void submit_should_fail_when_window_is_replaced_between_lookup_and_offer() {
        // Given：同一段窗口里发生的是「窗口被同会话的新回合顶掉」（与 endTurn 是两种关闭）
        queue.beginTurn("s1");
        queue.betweenWindowAndOffer = () -> queue.beginTurn("s1");

        // When
        ActionHandle handle = queue.submit("plugin-a", PluginAction.compact("s1"));

        // Then：同样必须落终态。旧窗口已经被收回，谁也不会再去取它
        assertTrue(handle.isFinished());
        assertEquals(ActionStatus.FAILED, handle.getStatus());
        assertEquals(ActionFailureReason.TURN_ENDED_UNREACHED, handle.getFailureReason());
    }

    @Test
    void beginTurn_should_fail_leftovers_when_same_session_window_is_replaced() {
        // 同一会话两个顶层回合是外壳该拦的事；动作通道只能认一个，且只能认最新的那个
        queue.beginTurn("s1");
        ActionHandle stranded = queue.submit("plugin-a",
                PluginAction.sendUserMessage("s1", "投给了旧窗口", DeliverAs.STEER));

        queue.beginTurn("s1");

        // 旧窗口再也不会被任何人取走：不收尾它就会永久停在 QUEUED，插件据此等终态就等不到
        assertEquals(ActionStatus.FAILED, stranded.getStatus());
        assertEquals(ActionFailureReason.TURN_SUPERSEDED, stranded.getFailureReason());
        assertTrue(stranded.getResult().contains("被同一会话的新回合取代"), stranded.getResult());
        assertTrue(stranded.isFinished());
    }
}
