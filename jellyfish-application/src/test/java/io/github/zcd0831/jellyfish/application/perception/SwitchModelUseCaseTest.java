package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.action.model.ContextLimit;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.ModelStatus;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.ProviderId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 显式切换模型：工具轮闭合是唯一硬闸；切换只改会话头。规则出处：04-perception §4.8.1 规则 2。 */
class SwitchModelUseCaseTest {

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RecordStore records = mock(RecordStore.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);

    private final SessionId sessionId = SessionId.of("s1");

    private SwitchModelUseCase useCase() {
        return new SwitchModelUseCase(sessions, records, catalog, commitScope, publisher);
    }

    private static Model model(String id, ModelStatus status) {
        return new Model(ModelId.of(id), ProviderId.of("p1"), id, status, new ContextLimit(1000, 100),
                Collections.emptyList());
    }

    private Session givenSession(List<Record> history) {
        Session session = Session.create(sessionId, null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
        when(sessions.load(sessionId)).thenReturn(session);
        when(records.last(sessionId, Integer.MAX_VALUE)).thenReturn(history);
        when(catalog.models()).thenReturn(java.util.Arrays.asList(
                model("m1", ModelStatus.ACTIVE), model("m2", ModelStatus.ACTIVE),
                model("m3", ModelStatus.DEPRECATED)));
        return session;
    }

    @Test
    void 工具轮已闭合时切换成功且只改会话头() {
        Session session = givenSession(Collections.<Record>emptyList());

        Result<Void> result = useCase().switchTo(sessionId, ModelId.of("m2"));

        assertTrue(result.isOk());
        assertEquals(ModelId.of("m2"), session.activeModelId());
        verify(commitScope).stageReplace(session);
        verify(publisher).publishAfterCommit(any());
    }

    @Test
    void 工具轮未闭合时拒绝且不改状态() {
        Session session = givenSession(Collections.<Record>emptyList());
        List<Record> history = new ArrayList<Record>();
        history.add(session.appendRecord(RecordRole.TOOL_REQUEST,
                "{\"callId\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\"}", null, "t1", "m1", null, 2L));
        when(records.last(sessionId, Integer.MAX_VALUE)).thenReturn(history);

        Result<Void> result = useCase().switchTo(sessionId, ModelId.of("m2"));

        assertFalse(result.isOk());
        assertEquals(SessionError.TOOL_ROUND_OPEN, result.error().error());
        assertEquals(ModelId.of("m1"), session.activeModelId());
        verify(commitScope, never()).commit();
    }

    @Test
    void 模型不存在或不可调用都拒绝() {
        givenSession(Collections.<Record>emptyList());

        assertEquals(io.github.zcd0831.jellyfish.domain.action.model.ModelError.MODEL_NOT_FOUND,
                useCase().switchTo(sessionId, ModelId.of("nope")).error().error());
        assertEquals(io.github.zcd0831.jellyfish.domain.action.model.ModelError.UNAVAILABLE,
                useCase().switchTo(sessionId, ModelId.of("m3")).error().error());
    }

    @Test
    void 切到同一个模型是幂等成功() {
        givenSession(Collections.<Record>emptyList());

        assertTrue(useCase().switchTo(sessionId, ModelId.of("m1")).isOk());
        verify(commitScope, never()).commit();
    }
}
