package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话恢复结果：插件交回它手上的全部会话快照。
 * <p>
 * 用结果对象而不是 {@code List<SessionSnapshot>}，是为了给「没有会话」与「一个也没读到」留出同一处表达，
 * 也留出将来扩展的余地（例如按目录分批、带来源标记）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionRestoreResult {

    /** 空结果：插件手上没有可恢复的会话。 */
    private static final SessionRestoreResult EMPTY = new SessionRestoreResult(null);

    /** 恢复出来的会话快照，保证非 {@code null}。 */
    private final List<SessionSnapshot> sessions;

    /**
     * 构造恢复结果。
     *
     * @param sessions 会话快照列表，可为 {@code null} 或空
     */
    private SessionRestoreResult(List<SessionSnapshot> sessions) {
        this.sessions = copySessions(sessions);
    }

    /**
     * 构造恢复结果。
     *
     * @param sessions 会话快照列表，可为 {@code null} 或空（等价 {@link #empty()}）
     * @return 恢复结果
     */
    public static SessionRestoreResult of(List<SessionSnapshot> sessions) {
        return sessions == null || sessions.isEmpty() ? EMPTY : new SessionRestoreResult(sessions);
    }

    /**
     * 构造空恢复结果。
     *
     * @return 没有任何会话的结果
     */
    public static SessionRestoreResult empty() {
        return EMPTY;
    }

    /**
     * 获取会话快照列表。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    public List<SessionSnapshot> getSessions() {
        return sessions;
    }

    @Override
    public String toString() {
        return "SessionRestoreResult{sessions=" + sessions.size() + '}';
    }

    /**
     * 复制会话列表并拒绝 {@code null} 元素。
     *
     * @param sessions 原始列表，可为 {@code null}
     * @return 不可变列表，保证非 {@code null}
     */
    private static List<SessionSnapshot> copySessions(List<SessionSnapshot> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return Collections.emptyList();
        }
        List<SessionSnapshot> copy = new ArrayList<SessionSnapshot>(sessions.size());
        for (SessionSnapshot session : sessions) {
            if (session == null) {
                throw new JellyfishException("session snapshot must not be null");
            }
            copy.add(session);
        }
        return Collections.unmodifiableList(copy);
    }
}
