package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子代理：各自独立成败、超时只终止该子代理、用量由发起线程归集。
 * 规则出处：05-action §5.9.1、§5.8.5。
 */
class SubagentLauncherTest {

    private static final SessionId PARENT = SessionId.of("s1");

    private final StartSessionUseCase startSession = mock(StartSessionUseCase.class);
    private final RunSessionUseCase runSession = mock(RunSessionUseCase.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RecordStore records = mock(RecordStore.class);
    private final BudgetService budgetService = mock(BudgetService.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final ProgressPort progress = mock(ProgressPort.class);
    private final SessionCancellations cancellations = new SessionCancellations();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private final Session parent = Session.create(PARENT, null, AgentId.of("a"), ModelId.of("m1"), 1L, null);

    private SubagentLauncher launcher(RuntimePolicy policy) {
        when(policies.load()).thenReturn(policy);
        when(records.last(any(SessionId.class), anyInt())).thenReturn(Collections.emptyList());
        when(sessions.load(any(SessionId.class))).thenAnswer(call -> {
            Session child = Session.create(call.getArgument(0), PARENT, AgentId.of("a"), ModelId.of("m1"),
                    1L, null);
            child.retitle("结论");
            return child;
        });
        return new SubagentLauncher(startSession, new RunSessionProvider() {
            @Override
            public RunSessionUseCase get() {
                return runSession;
            }
        }, sessions, records, budgetService, policies, progress, pool, cancellations);
    }

    private void subSessionsAreNew() {
        final AtomicInteger counter = new AtomicInteger();
        when(startSession.startSubSession(any(SessionId.class), any(), any(), any(), any()))
                .thenAnswer(call -> Result.ok(SessionId.of("c-" + counter.incrementAndGet())));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void 各子代理独立成败且用量由发起线程归集() {
        subSessionsAreNew();
        when(runSession.run(any(SessionId.class), any(), any(ChunkSink.class)))
                .thenReturn(Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CONVERGED)));
        List<SubagentRequest> requests = new ArrayList<SubagentRequest>();
        requests.add(new SubagentRequest(AgentId.of("a"), ModelId.of("m1"), "第一件"));
        requests.add(new SubagentRequest(AgentId.of("a"), ModelId.of("m1"), "第二件"));

        List<SubagentResult> results = launcher(RuntimePolicy.defaults()).launch(parent, requests);

        assertEquals(2, results.size());
        for (SubagentResult result : results) {
            assertEquals("结论", result.conclusion());
        }
        verify(budgetService).settleFromSubSession(SessionId.of("c-1"));
        verify(budgetService).settleFromSubSession(SessionId.of("c-2"));
    }

    /**
     * 超时只终止该子代理：主会话继续，结果按“已终止（超时）”回给模型（05-action §5.9.1）。
     */
    @Test
    void 超时只终止该子代理() throws Exception {
        subSessionsAreNew();
        final CountDownLatch release = new CountDownLatch(1);
        when(runSession.run(any(SessionId.class), any(), any(ChunkSink.class))).thenAnswer(call -> {
            release.await(5, TimeUnit.SECONDS);   // 模拟迟迟不返回的子代理
            return Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CONVERGED));
        });
        RuntimePolicy impatient = RuntimePolicy.defaults().adjust(
                Collections.<String, Object>singletonMap("subagentExecutionTimeoutMillis", 50));

        try {
            List<SubagentResult> results = launcher(impatient).launch(parent,
                    Collections.singletonList(new SubagentRequest(AgentId.of("a"), ModelId.of("m1"), "卡住")));

            assertEquals(1, results.size());
            assertTrue(results.get(0).conclusion().contains("超时"), results.get(0).conclusion());
        } finally {
            release.countDown();
        }
    }
}
