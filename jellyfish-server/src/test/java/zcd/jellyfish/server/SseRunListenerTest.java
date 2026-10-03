package zcd.jellyfish.server;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.core.runtime.AgentRunEvent;
import zcd.jellyfish.core.runtime.AgentRunRequest;
import zcd.jellyfish.core.runtime.AgentRunSnapshot;
import zcd.jellyfish.core.runtime.AgentRunStatus;
import zcd.jellyfish.core.runtime.RunRegistry;
import zcd.jellyfish.infra.session.SessionUsage;
import zcd.jellyfish.server.dto.RunFinishedEvent;
import zcd.jellyfish.server.dto.RunStartedEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SseRunListener} 的单元测试：事件映射与按父会话过滤。
 * <p>
 * 快照用真实的 {@link RunRegistry} 造：本类要验证的是「快照字段有没有被正确搬进 SSE 载荷」，
 * 手搓一个快照会把这条链断在验证之外。
 *
 * @author zcd
 */
class SseRunListenerTest {

    /** 本流所属的会话。 */
    private static final String SESSION = "s1";

    @Test
    void accept_should_map_started_and_finished_events() {
        // Given
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(new AgentRunRequest(SESSION, "coder", "s1-child", "call-1"),
                null, null);
        registry.markRunning(runId);
        SseRunListener listener = new SseRunListener(SESSION);

        // When：开始
        AgentRunSnapshot running = registry.snapshot(runId).get();
        listener.accept(AgentRunEvent.started(running));

        // Then
        SseEvent started = listener.pollNow();
        assertEquals("run_started", started.getName());
        RunStartedEvent startedPayload = (RunStartedEvent) started.getPayload();
        assertEquals(runId, startedPayload.getRunId());
        assertEquals(SESSION, startedPayload.getParentSessionId());
        assertEquals("coder", startedPayload.getAgentId());
        assertEquals("s1-child", startedPayload.getSessionId());
        assertTrue(!started.isTerminal());

        // When：终结
        registry.finish(runId, AgentRunStatus.DONE, 3, SessionUsage.EMPTY);
        AgentRunSnapshot done = registry.snapshot(runId).get();
        listener.accept(AgentRunEvent.finished(done));

        // Then
        SseEvent finished = listener.pollNow();
        assertEquals("run_finished", finished.getName());
        RunFinishedEvent finishedPayload = (RunFinishedEvent) finished.getPayload();
        assertEquals(runId, finishedPayload.getRunId());
        assertEquals("DONE", finishedPayload.getStatus());
        assertEquals(3, finishedPayload.getRounds());
        assertEquals(done.getFinishedAt(), finishedPayload.getFinishedAt());
    }

    @Test
    void accept_should_ignore_runs_of_other_sessions() {
        // 总线是进程级的：不过滤的话，A 会话的客户端会看到 B 会话的子代理在跑
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(new AgentRunRequest("s2", "coder", "s2-child", "call-2"),
                null, null);
        SseRunListener listener = new SseRunListener(SESSION);

        listener.accept(AgentRunEvent.started(registry.snapshot(runId).get()));

        assertNull(listener.pollNow());
    }

    @Test
    void accept_should_ignore_step_events_for_now() {
        // STEP 与输出流是另一档（要 loop 进度钩子 / 可丢通道），本步不映射
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(new AgentRunRequest(SESSION, "coder", "s1-child", "call-1"),
                null, null);
        SseRunListener listener = new SseRunListener(SESSION);

        listener.accept(AgentRunEvent.step(registry.snapshot(runId).get(), "grep_files"));

        assertNull(listener.pollNow());
    }
}
