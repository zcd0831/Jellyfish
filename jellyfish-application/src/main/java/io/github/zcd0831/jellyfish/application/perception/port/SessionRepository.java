package io.github.zcd0831.jellyfish.application.perception.port;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import java.util.List;

/**
 * 会话头与状态。规则出处：模块-基础设施层 §3。
 */
public interface SessionRepository {

    Session load(SessionId id);

    void save(Session session);

    List<SessionId> listResumable();

    void archive(SessionId id);

    void delete(SessionId id);
}
