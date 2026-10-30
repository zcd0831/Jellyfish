package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.SessionToolScope;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fork 型子会话与终止：登记 → 内循环 → 注销；终止上抛、环境问题静默。
 * 规则出处：08-application §8.9.4.1、§8.9.7。
 */
class InternalSessionRunnerTest {

    private static final SessionId PARENT = SessionId.of("s1");
    private static final SessionId CHILD = SessionId.of("c1");

    private final StartSessionUseCase startSession = mock(StartSessionUseCase.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final ReActLoop reActLoop = mock(ReActLoop.class);
    private final SessionCancellations cancellations = new SessionCancellations();

    private final Session parent = Session.create(PARENT, null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
    private final Session child = Session.create(CHILD, PARENT, AgentId.of("a"), ModelId.of("m1"), 1L, null);

    private InternalSessionRunner runner() {
        return new InternalSessionRunner(startSession, sessions, reActLoop, new SessionToolScope(),
                new io.github.zcd0831.jellyfish.application.shared.port.CommitScope() {
                    @Override
                    public void stageAppend(SessionId sessionId,
                                            io.github.zcd0831.jellyfish.domain.perception.session.Record record) {
                    }

                    @Override
                    public void stageReplace(Object aggregate) {
                    }

                    @Override
                    public void stageFact(Object fact) {
                    }

                    @Override
                    public void globalWrite(Runnable work) {
                        work.run();
                    }

                    @Override
                    public void commit() {
                    }
                }, () -> 1L, cancellations);
    }

    /**
     * fork 结束（含异常路径）必须注销：否则父终止级联会牵着早已结束的子会话，
     * 长会话里登记表只增不减。规则出处：08-application §8.9.4.1。
     */
    @Test
    void 子会话结束后注销令牌() {
        cancellations.register(PARENT, null);
        when(startSession.forkInternal(PARENT)).thenReturn(Result.ok(CHILD));
        when(sessions.load(CHILD)).thenReturn(child);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenThrow(new CancellationException("用户中断"));

        assertThrows(CancellationException.class, () -> runner().run(parent, "反思一下"));

        cancellations.cancel(PARENT);
        assertFalse(cancellations.forSession(CHILD).isCancelled(),
                "已注销的子会话不该再被父级联牵连");
    }

    /**
     * 创建阶段就被终止：父令牌已终止 ⇒ 上抛，调用方按“本轮 CANCELLED”处理。
     * 规则出处：08-application §8.9.4.1（父已终止则子令牌立即终止）。
     */
    @Test
    void fork未成立且父已终止则上抛取消() {
        cancellations.register(PARENT, null);
        when(startSession.forkInternal(PARENT))
                .thenReturn(Result.<SessionId>fail(UseCaseError.of(SessionError.NOT_FOUND, "子会话被终止，未创建", CHILD)));
        cancellations.cancel(PARENT);

        assertThrows(CancellationException.class, () -> runner().run(parent, "反思一下"));
    }

    /**
     * fork 未成立但父没被终止：只是环境问题 ⇒ 本次不产出（静默），不能误判成终止。
     * 规则出处：08-application §8.10（沉淀 Draft 不可解析 / 失败 ⇒ 本次不产出，会话继续）。
     */
    @Test
    void fork未成立且父未终止则本次不产出() {
        when(startSession.forkInternal(PARENT))
                .thenReturn(Result.<SessionId>fail(UseCaseError.of(SessionError.NOT_FOUND, "父会话不存在", PARENT)));

        assertNull(runner().run(parent, "反思一下"));
        assertEquals(Collections.emptyList(), parent.records());
    }
}
