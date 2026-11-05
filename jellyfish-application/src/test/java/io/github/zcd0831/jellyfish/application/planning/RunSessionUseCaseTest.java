package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.perception.session.SessionHeader;
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
import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
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

    private final Session session = Session.create(SessionHeader
            .builder(SessionId.of("s1"), AgentId.of("a"), ModelId.of("m1")).createdDate(1L).build());

    /** 运行权登记表是进程级共享的，所以测试里也只建一份（08-application §8.11）。 */
    private final SessionCancellations cancellations = new SessionCancellations();

    private RunSessionUseCase useCase() {
        return useCase(session -> { });
    }

    private RunSessionUseCase useCase(io.github.zcd0831.jellyfish.application.shared.SessionResidueReconciler reconciler) {
        when(sessions.load(SessionId.of("s1"))).thenReturn(session);
        when(records.last(any(SessionId.class), anyInt())).thenReturn(Collections.emptyList());
        return new RunSessionUseCase(sessions, records, commitScope, events -> { }, runner, model, tools, human,
                progress, plans, contextWindow, cancellations, reconciler);
    }

    @Test
    void 会话不存在即失败() {
        Result<RunSessionResult> result = useCase().run(SessionId.of("nope"), "hi", ChunkSink.NOOP);

        assertFalse(result.isOk());
        assertEquals("S_NOT_FOUND", result.error().error().code());
    }

    @Test
    void 续跑只发应用事件SessionResumed且不追用户消息() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class)))
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
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class))).thenReturn(RunOutcome.CONVERGED);

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());
        verify(runner).run(any(Session.class), any(ChunkSink.class), any(RunContext.class));
    }

    @Test
    void 挂起如实映射() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class)))
                .thenReturn(RunOutcome.SUSPENDED);
        assertEquals(RunSessionResult.Outcome.SUSPENDED,
                useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP).value().outcome());
    }

    @Test
    void 取消是正常结果不是错误() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class)))
                .thenThrow(new CancellationException("用户中断"));

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CANCELLED, result.value().outcome());
    }

    /**
     * 子代理的令牌必须先于会话存在（创建期就要能终止），但那个令牌不能被当成"已有 run 在跑"。
     * 规则出处：08-application §8.11；症状是 spawn_subagents 全线 S_RUN_IN_PROGRESS。
     */
    @Test
    void 会话创建期已登记令牌时仍可运行() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class)))
                .thenReturn(RunOutcome.CONVERGED);
        io.github.zcd0831.jellyfish.application.shared.CancellationToken preRegistered =
                cancellations.beginChild(SessionId.of("parent"));
        cancellations.attach(SessionId.of("s1"), SessionId.of("parent"), preRegistered);

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertTrue(result.isOk(), "创建期登记的令牌不能占掉运行权");
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());
    }

    @Test
    void 会话创建期已终止的令牌让run直接处于已终止() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class)))
                .thenThrow(new CancellationException("父已终止"));
        io.github.zcd0831.jellyfish.application.shared.CancellationToken preRegistered =
                cancellations.beginChild(SessionId.of("parent"));
        cancellations.attach(SessionId.of("s1"), SessionId.of("parent"), preRegistered);
        cancellations.cancel(SessionId.of("s1"));

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertEquals(RunSessionResult.Outcome.CANCELLED, result.value().outcome());
    }

    @Test
    void 模型不可用不改状态() {
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class)))
                .thenThrow(new ModelInvocationException(ModelError.UNAVAILABLE, "全挂"));
        Session fresh = Session.create(SessionHeader
                .builder(SessionId.of("s1"), AgentId.of("a"), ModelId.of("m1")).createdDate(1L).build());
        when(sessions.load(SessionId.of("s1"))).thenReturn(fresh);

        Result<RunSessionResult> result = useCase().run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertFalse(result.isOk());
        assertEquals("M_UNAVAILABLE", result.error().error().code());
        verify(runner).run(any(Session.class), any(ChunkSink.class), any(RunContext.class));
    }

    /**
     * 同一会话同时只允许一个 run：第二个在第一个还没返回时来抢，必须被拒（不排队、不并发），
     * 否则两个 run 会同时读到“没有运行中的计划”，把单计划守卫绕过去。
     * 规则出处：08-application §8.11。
     */
    @Test
    void 同一会话已有run在跑时拒绝第二次() {
        RunSessionUseCase useCase = useCase();
        final int[] rejected = new int[1];
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class))).thenAnswer(call -> {
            Result<RunSessionResult> second = useCase.run(SessionId.of("s1"), "再来一句", ChunkSink.NOOP);
            assertFalse(second.isOk());
            assertEquals(SessionError.RUN_IN_PROGRESS, second.error().error());
            rejected[0]++;
            return RunOutcome.CONVERGED;
        });

        assertEquals(RunSessionResult.Outcome.CONVERGED,
                useCase.run(SessionId.of("s1"), "hi", ChunkSink.NOOP).value().outcome());
        assertEquals(1, rejected[0]);
        // 跑完之后运行权已释放：同一会话可以再次进入
        assertEquals(RunSessionResult.Outcome.CONVERGED,
                useCase.run(SessionId.of("s1"), "第二轮", ChunkSink.NOOP).value().outcome());
    }
    /** 会话要跑之前先对账：崩溃残留不能等到 buildContextForLlm 才发现（08-application §8.9.3）。 */
    @Test
    void 跑之前先对账这一个会话() {
        final java.util.List<String> reconciled = new java.util.ArrayList<String>();
        when(runner.run(any(Session.class), any(ChunkSink.class), any(RunContext.class)))
                .thenReturn(RunOutcome.CONVERGED);
        useCase(session -> reconciled.add(session.id().getValue())).run(SessionId.of("s1"), "hi", ChunkSink.NOOP);

        assertEquals(java.util.Collections.singletonList("s1"), reconciled);
    }

    @Test
    void 会话不存在时不对账() {
        final java.util.List<String> reconciled = new java.util.ArrayList<String>();
        useCase(session -> reconciled.add(session.id().getValue())).run(SessionId.of("nope"), "hi", ChunkSink.NOOP);

        assertTrue(reconciled.isEmpty());
    }

}
