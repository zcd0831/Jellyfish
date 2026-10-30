package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.ToolInvoker;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 一轮运行的四条出口：收敛 / 挂起 / 轮次用尽 / 取消 / 模型不可用。 */
class RunSessionUseCaseTest {

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RecordStore records = mock(RecordStore.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final SessionRunner runner = mock(SessionRunner.class);

    private final ModelInvocationPort model = mock(ModelInvocationPort.class);
    private final ToolInvoker tools = mock(ToolInvoker.class);
    private final HumanInteractionPort human = mock(HumanInteractionPort.class);
    private final ProgressPort progress = mock(ProgressPort.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final ContextWindowService contextWindow = mock(ContextWindowService.class);

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private RunSessionUseCase useCase() {
        when(sessions.load(SessionId.of("s1"))).thenReturn(session);
        when(records.last(any(SessionId.class), anyInt())).thenReturn(Collections.emptyList());
        return new RunSessionUseCase(sessions, records, commitScope, events -> { }, runner, model, tools, human,
                progress, plans, contextWindow, new SessionCancellations());
    }

    @Test
    void 会话不存在即失败() {
        Result<RunSessionResult> result = useCase().run(SessionId.of("nope"), "hi", ChunkSink.NOOP);

        assertFalse(result.isOk());
        assertEquals("S_NOT_FOUND", result.error().error().code());
    }

    @Test
    void 续跑只发应用事件SessionResumed且不追用户消息() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(), any(RunContext.class)))
                .thenReturn(RunOutcome.CONVERGED);

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), null, ChunkSink.NOOP);

        assertTrue(result.isOk());
        org.mockito.ArgumentCaptor<io.github.zcd0831.jellyfish.application.shared.ProgressEvent> captor =
                org.mockito.ArgumentCaptor.forClass(io.github.zcd0831.jellyfish.application.shared.ProgressEvent.class);
        verify(progress).emit(captor.capture());
        assertEquals(io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.SESSION_RESUMED,
                captor.getValue().kind());
        assertTrue(session.records().isEmpty(), "续跑不追加 USER（08-application §8.9.3）");
    }

    @Test
    void 收敛后把本轮事实落盘() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(), any(RunContext.class))).thenReturn(RunOutcome.CONVERGED);

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());
        verify(runner).run(any(Session.class), any(ChunkSink.class), any(), any(RunContext.class));
    }

    @Test
    void 挂起与轮次用尽如实映射() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(), any(RunContext.class)))
                .thenReturn(RunOutcome.SUSPENDED);
        assertEquals(RunSessionResult.Outcome.SUSPENDED,
                useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP).value().outcome());

        when(runner.run(any(Session.class), any(ChunkSink.class), any(), any(RunContext.class)))
                .thenReturn(RunOutcome.ROUNDS_EXHAUSTED);
        assertEquals(RunSessionResult.Outcome.ROUNDS_EXHAUSTED,
                useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP).value().outcome());
    }

    @Test
    void 取消是正常结果不是错误() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(), any(RunContext.class)))
                .thenThrow(new CancellationException("用户中断"));

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CANCELLED, result.value().outcome());
    }

    @Test
    void 模型不可用不改状态() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(), any(RunContext.class)))
                .thenThrow(new ModelInvocationException(ModelError.UNAVAILABLE, "全挂"));
        Session fresh = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
        when(sessions.load(SessionId.of("s1"))).thenReturn(fresh);

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertFalse(result.isOk());
        assertEquals("M_UNAVAILABLE", result.error().error().code());
        verify(runner).run(any(Session.class), any(ChunkSink.class), any(), any(RunContext.class));
    }
}
