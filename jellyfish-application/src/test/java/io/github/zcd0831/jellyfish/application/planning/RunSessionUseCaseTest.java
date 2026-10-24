package io.github.zcd0831.jellyfish.application.planning;

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
    private final ExecutionLoop executionLoop = mock(ExecutionLoop.class);

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private RunSessionUseCase useCase() {
        when(sessions.load(SessionId.of("s1"))).thenReturn(session);
        when(records.last(any(SessionId.class), anyInt())).thenReturn(Collections.emptyList());
        return new RunSessionUseCase(sessions, records, commitScope, events -> { }, executionLoop);
    }

    @Test
    void 会话不存在即失败() {
        Result<RunSessionResult> result = useCase().run(SessionId.of("nope"), "hi", ChunkSink.NOOP);

        assertFalse(result.isOk());
        assertEquals("S_NOT_FOUND", result.error().error().code());
    }

    @Test
    void 收敛后把本轮事实落盘() {
        when(executionLoop.run(any(Session.class), any(ChunkSink.class), any())).thenReturn(ExecutionLoop.Outcome.CONVERGED);

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());
        verify(executionLoop).run(any(Session.class), any(ChunkSink.class), any());
    }

    @Test
    void 挂起与轮次用尽如实映射() {
        when(executionLoop.run(any(Session.class), any(ChunkSink.class), any()))
                .thenReturn(ExecutionLoop.Outcome.SUSPENDED);
        assertEquals(RunSessionResult.Outcome.SUSPENDED,
                useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP).value().outcome());

        when(executionLoop.run(any(Session.class), any(ChunkSink.class), any()))
                .thenReturn(ExecutionLoop.Outcome.ROUNDS_EXHAUSTED);
        assertEquals(RunSessionResult.Outcome.ROUNDS_EXHAUSTED,
                useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP).value().outcome());
    }

    @Test
    void 取消是正常结果不是错误() {
        when(executionLoop.run(any(Session.class), any(ChunkSink.class), any()))
                .thenThrow(new CancellationException("用户中断"));

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CANCELLED, result.value().outcome());
    }

    @Test
    void 模型不可用不改状态() {
        when(executionLoop.run(any(Session.class), any(ChunkSink.class), any()))
                .thenThrow(new ModelInvocationException(ModelError.UNAVAILABLE, "全挂"));
        Session fresh = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
        when(sessions.load(SessionId.of("s1"))).thenReturn(fresh);

        Result<RunSessionResult> result = new RunSessionUseCase(sessions, records, commitScope, events -> { },
                executionLoop).run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertFalse(result.isOk());
        assertEquals("M_UNAVAILABLE", result.error().error().code());
        verify(executionLoop).run(any(Session.class), any(ChunkSink.class), any());
    }
}
