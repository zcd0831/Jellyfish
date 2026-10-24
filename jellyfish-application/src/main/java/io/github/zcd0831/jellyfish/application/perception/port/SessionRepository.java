package io.github.zcd0831.jellyfish.application.perception.port;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.List;

/**
 * 会话头与存放策略（模块-基础设施层 §3）。
 * <p>
 * 会话**没有状态**：{@code sessions/<id>/} 存在即可用，不存在即不可用（含已归档）。
 * 归档是存放策略——压成包移走，此后应用不再认识它（D22）。
 */
public interface SessionRepository {

    Session load(SessionId id);

    void save(Session session);

    /** 列出所有存在的会话（含子会话），供界面选择要加载哪个。 */
    List<SessionSummary> list();

    /** 压成 {@code archive/<id>.zip} 后删除原目录；失败不留半成品。 */
    void archive(SessionId id);

    void delete(SessionId id);

    /** 归档中间态对账：清理 {@code .tmp} 包、补删"包已完整但目录还在"的原目录。 */
    void cleanupArchiveResidue();
}
