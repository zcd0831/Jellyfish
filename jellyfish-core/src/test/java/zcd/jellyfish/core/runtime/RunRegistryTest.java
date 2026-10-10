package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.session.SessionUsage;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunRegistry} 的单元测试：身份、树根继承、状态转换的幂等、取消的子树语义与查询。
 * <p>
 * 最要紧的一条是「终态恰好一个」：调度器与看门狗可能同时想终结同一个 run，先到的那个必须胜出。
 * 其次是「开始执行时刻只记一次」与「取消中」这两件事——它们只服务于观测（面板的时长与状态词），
 * 却必须准：显示不准比不显示更糟。
 *
 * @author zcd
 */
class RunRegistryTest {

    @Test
    void register_should_return_identity_and_pending_snapshot() {
        // Given
        RunRegistry registry = new RunRegistry();

        // When
        String runId = registry.register(request("s-child"), null, null);

        // Then
        assertNotNull(runId);
        AgentRunSnapshot snapshot = registry.snapshot(runId).orElseThrow(AssertionError::new);
        assertEquals(AgentRunStatus.PENDING, snapshot.getStatus());
        assertEquals(runId, snapshot.getRootRunId());
        assertEquals("coder", snapshot.getAgentId());
        assertEquals("s-child", snapshot.getSessionId());
        assertEquals("s-parent", snapshot.getParentSessionId());
        assertNull(snapshot.getParentRunId());
    }

    @Test
    void register_should_inherit_root_from_parent_root() {
        // Given：先有一个根 run
        RunRegistry registry = new RunRegistry();
        String rootRunId = registry.register(request("s-root"), null, null);

        // When：再登记它的孩子，父根显式传入
        String childRunId = registry.register(request("s-child"), rootRunId, rootRunId);

        // Then：孩子与根共享同一个 rootRunId，且 parentRunId 指向父
        AgentRunSnapshot child = registry.snapshot(childRunId).orElseThrow(AssertionError::new);
        assertEquals(rootRunId, child.getRootRunId());
        assertEquals(rootRunId, child.getParentRunId());
    }

    @Test
    void register_should_use_self_as_root_when_parent_root_absent() {
        // When：父根缺失（父即根，或没有父）
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(request("s-child"), "some-parent", null);

        // Then：自己成为树根
        assertEquals(runId, registry.snapshot(runId).orElseThrow(AssertionError::new).getRootRunId());
    }

    @Test
    void markRunning_should_transition_from_pending() {
        // Given
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(request("s-child"), null, null);

        // When
        boolean changed = registry.markRunning(runId);

        // Then
        assertTrue(changed);
        assertEquals(AgentRunStatus.RUNNING, registry.snapshot(runId).orElseThrow(AssertionError::new).getStatus());
    }

    @Test
    void markRunning_should_return_false_when_run_unknown() {
        // When / Then
        assertFalse(new RunRegistry().markRunning("missing"));
    }

    @Test
    void markRunning_should_record_start_time_once() {
        // Given：一个刚登记的 run——此刻还没开跑，快照里没有开始执行的时刻
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(request("s-child"), null, null);
        assertEquals(0L, registry.snapshot(runId).orElseThrow(AssertionError::new).getRunningAt());

        // When：真正开始跑
        registry.markRunning(runId);
        long runningAt = registry.snapshot(runId).orElseThrow(AssertionError::new).getRunningAt();
        assertTrue(runningAt > 0L);

        // Then：等完孩子接着跑（WAITING_CHILDREN → RUNNING）不重新计时——
        // 否则一个跑了十分钟的父 run 在界面上会显示成刚跑了一秒
        registry.markWaitingChildren(runId);
        registry.markRunning(runId);
        assertEquals(runningAt,
                registry.snapshot(runId).orElseThrow(AssertionError::new).getRunningAt());
    }

    @Test
    void snapshot_should_report_cancelling_when_handle_cancelled_and_not_settled() {
        // Given：一个在跑、句柄已绑定的 run
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(request("s-child"), null, null);
        AgentRunHandle handle = new AgentRunHandle(runId);
        registry.bindHandle(runId, handle);
        registry.markRunning(runId);
        assertFalse(registry.snapshot(runId).orElseThrow(AssertionError::new).isCancelling());

        // When：取消被请求，但它还没走到检查点（状态仍是运行中）
        registry.cancelTree(runId);

        // Then：快照如实说「取消中」——界面靠它才能把「按了中断」与「没按上」区分开
        assertTrue(registry.snapshot(runId).orElseThrow(AssertionError::new).isCancelling());

        // 收敛之后不再是「取消中」：终态就是终态
        registry.finish(runId, AgentRunStatus.CANCELLED, 1, null);
        assertFalse(registry.snapshot(runId).orElseThrow(AssertionError::new).isCancelling());
    }

    @Test
    void cancelTree_should_cancel_self_and_descendants() {
        // Given：一棵三层树，每层都绑了句柄
        RunRegistry registry = new RunRegistry();
        String root = registry.register(request("s-root"), null, null);
        String child = registry.register(request("s-child"), root, root);
        String grandChild = registry.register(request("s-grand"), child, root);
        AgentRunHandle rootHandle = new AgentRunHandle(root);
        AgentRunHandle childHandle = new AgentRunHandle(child);
        AgentRunHandle grandChildHandle = new AgentRunHandle(grandChild);
        registry.bindHandle(root, rootHandle);
        registry.bindHandle(child, childHandle);
        registry.bindHandle(grandChild, grandChildHandle);

        // When：取消树根
        int cancelled = registry.cancelTree(root);

        // Then：三代都被取消——没有父收集者的子 run 继续跑毫无意义
        assertEquals(3, cancelled);
        assertTrue(rootHandle.isCancelled());
        assertTrue(childHandle.isCancelled());
        assertTrue(grandChildHandle.isCancelled());
    }

    @Test
    void cancelDescendants_should_leave_self_alone() {
        // Given：父 run 与它的孩子
        RunRegistry registry = new RunRegistry();
        String parent = registry.register(request("s-parent"), null, null);
        String child = registry.register(request("s-child"), parent, parent);
        AgentRunHandle parentHandle = new AgentRunHandle(parent);
        AgentRunHandle childHandle = new AgentRunHandle(child);
        registry.bindHandle(parent, parentHandle);
        registry.bindHandle(child, childHandle);

        // When：调度器在父 run 收尾时清理遗留子树
        int cancelled = registry.cancelDescendants(parent);

        // Then：只取消后代，父自己不动（它已经收尾了）
        assertEquals(1, cancelled);
        assertFalse(parentHandle.isCancelled());
        assertTrue(childHandle.isCancelled());
    }

    @Test
    void cancelTree_should_return_zero_when_run_unknown() {
        // When / Then
        assertEquals(0, new RunRegistry().cancelTree("missing"));
    }

    @Test
    void finish_should_record_terminal_state_rounds_and_tokens() {
        // Given
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(request("s-child"), null, null);
        registry.markRunning(runId);

        // When
        SessionUsage usage = new SessionUsage(10L, 5L, 15L, 1L);
        boolean changed = registry.finish(runId, AgentRunStatus.DONE, 3, usage);

        // Then
        assertTrue(changed);
        AgentRunSnapshot snapshot = registry.snapshot(runId).orElseThrow(AssertionError::new);
        assertEquals(AgentRunStatus.DONE, snapshot.getStatus());
        assertEquals(3, snapshot.getRounds());
        assertEquals(15L, snapshot.getTotalTokens());
        assertTrue(snapshot.getFinishedAt() > 0L);
    }

    @Test
    void finish_should_be_idempotent_and_keep_first_terminal_state() {
        // Given：第一个终结者先到
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(request("s-child"), null, null);
        registry.markRunning(runId);
        registry.finish(runId, AgentRunStatus.DONE, 3, null);

        // When：第二个终结者（例如看门狗）随后到达
        boolean changed = registry.finish(runId, AgentRunStatus.TRUNCATED, 9, null);

        // Then：先到的胜出，后者不改写
        assertFalse(changed);
        AgentRunSnapshot snapshot = registry.snapshot(runId).orElseThrow(AssertionError::new);
        assertEquals(AgentRunStatus.DONE, snapshot.getStatus());
        assertEquals(3, snapshot.getRounds());
    }

    @Test
    void finish_should_reject_non_terminal_status() {
        // Given
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(request("s-child"), null, null);

        // When / Then
        assertThrows(IllegalArgumentException.class,
                () -> registry.finish(runId, AgentRunStatus.RUNNING, 0, null));
    }

    @Test
    void finish_should_return_false_when_run_unknown() {
        // When / Then
        assertFalse(new RunRegistry().finish("missing", AgentRunStatus.DONE, 0, null));
    }

    @Test
    void active_should_exclude_terminal_runs() {
        // Given：一个还在跑、一个已经终结
        RunRegistry registry = new RunRegistry();
        String running = registry.register(request("s-running"), null, null);
        registry.markRunning(running);
        String done = registry.register(request("s-done"), null, null);
        registry.markRunning(done);
        registry.finish(done, AgentRunStatus.DONE, 1, null);

        // When
        List<AgentRunSnapshot> active = registry.active();

        // Then
        assertEquals(1, active.size());
        assertEquals(running, active.get(0).getRunId());
    }

    @Test
    void remove_should_drop_entry() {
        // Given
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(request("s-child"), null, null);

        // When
        Optional<AgentRunSnapshot> removed = registry.remove(runId);

        // Then
        assertTrue(removed.isPresent());
        assertFalse(registry.snapshot(runId).isPresent());
    }

    @Test
    void snapshot_should_be_empty_when_run_unknown() {
        // When / Then
        assertFalse(new RunRegistry().snapshot("missing").isPresent());
    }

    /**
     * 构造一个登记输入：除会话外，其余字段固定。
     *
     * @param sessionId 本 run 会话标识
     * @return 登记输入
     */
    private static AgentRunRequest request(String sessionId) {
        return new AgentRunRequest("s-parent", "coder", sessionId, "call-1");
    }
}
