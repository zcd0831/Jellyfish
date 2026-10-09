package zcd.jellyfish.infra.session;

import zcd.jellyfish.api.extension.SessionCompactionSnapshot;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.SessionToolCallSnapshot;
import zcd.jellyfish.api.extension.SessionUsageSnapshot;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.llm.LlmUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话快照的双向映射：内核会话模型 ↔ api 侧快照。
 * <p>
 * <b>为什么必须有这一层</b>：会话持久化是插件的职责，而 {@code Session} / {@code LlmMessage} 是
 * {@code jellyfish-infra} 的类型，插件看不到。于是每次需要落盘时，内核把会话投影成
 * {@link SessionSnapshot} 交给插件；回放时把快照还原成 {@code Session}。
 * <p>
 * <b>放 infra 而不是 core</b>：它要认识 {@code Session} 与 {@code LlmMessage}，
 * 而这两个模型就住在 infra——映射逻辑与模型同域，将来模型加字段时改动落在同一个包内。
 * <p>
 * <b>保真性由往返测试守护</b>：{@code capture → restore → capture} 必须得到相等的快照，
 * 这是「快照字段漏了一个」唯一能被自动发现的地方。
 *
 * @author zcd
 */
public final class SessionSnapshots {

    /**
     * 工具类，禁止实例化。
     */
    private SessionSnapshots() {
    }

    /**
     * 把内核会话投影成 api 快照。
     * <p>
     * <b>不要从外部调它</b>：本方法逐个读 {@code session} 的同步 getter，两次读之间没有任何东西
     * 把它们绑在一起，因此只有在**持着会话实例锁**的情况下调用才能得到一致的投影。唯一入口是
     * {@link Session#captureSnapshot()}——本方法收成包私有正是为了这一点：以前它是 public，
     * 于是「一致投影」只被修在落盘路径上，而归档、HTTP 响应与 Spring 查询三处仍在逐个读。
     * 包内只有 {@link Session#captureSnapshot()} 调它（测试里另有一处，用来对照「不持锁会怎样」）。
     *
     * @param session 会话运行态，不可为 {@code null}
     * @return 会话快照
     */
    static SessionSnapshot capture(Session session) {
        List<SessionMessageSnapshot> messages = new ArrayList<SessionMessageSnapshot>(session.size());
        for (SessionMessage message : session.getMessages()) {
            messages.add(captureMessage(message));
        }
        // 一致投影的易破处就在这一行：消息已经读完、用量还没读。持着实例锁时它是无害的
        // （别的线程进不来），不持锁时别的线程能在这里插进来，快照随即自相矛盾
        Session.betweenSnapshotReads.run();
        return new SessionSnapshot(session.getSessionId(), session.getCreatedAt(), session.getUpdatedAt(),
                session.getTitle(), session.getAgentId(), session.getProvider(), session.getModel(),
                messages, captureUsage(session.getUsage()),
                captureCompaction(session.getCompaction()), session.getKind(), session.getParentSessionId(),
                session.getForkPointMessageId(), session.getExtensionEntries());
    }

    /**
     * 投影压缩摘要。
     *
     * @param compaction 压缩摘要，可为 {@code null}
     * @return 摘要快照，入参为 {@code null} 时返回 {@code null}
     */
    private static SessionCompactionSnapshot captureCompaction(SessionCompaction compaction) {
        if (compaction == null) {
            return null;
        }
        return new SessionCompactionSnapshot(compaction.getSummary(), compaction.getBoundaryMessageId(),
                compaction.getCreatedAt(), compaction.getDroppedMessageCount());
    }

    /**
     * 还原压缩摘要。
     *
     * @param snapshot 摘要快照，可为 {@code null}
     * @return 压缩摘要，入参为 {@code null} 时返回 {@code null}
     */
    static SessionCompaction toCompaction(SessionCompactionSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new SessionCompaction(snapshot.getSummary(), snapshot.getBoundaryMessageId(),
                snapshot.getCreatedAt(), snapshot.getDroppedMessageCount());
    }

    /**
     * 还原单条消息。
     *
     * @param snapshot 消息快照，不可为 {@code null}
     * @return 会话消息
     */
    static SessionMessage toMessage(SessionMessageSnapshot snapshot) {
        List<LlmToolCall> toolCalls = new ArrayList<LlmToolCall>(snapshot.getToolCalls().size());
        for (SessionToolCallSnapshot toolCall : snapshot.getToolCalls()) {
            toolCalls.add(new LlmToolCall(toolCall.getIndex(), toolCall.getId(), toolCall.getName(),
                    toolCall.getArguments()));
        }
        LlmMessage message = new LlmMessage(snapshot.getRole(), snapshot.getContent(),
                snapshot.getToolCallId(), snapshot.getName(), toolCalls);
        return new SessionMessage(snapshot.getMessageId(), snapshot.getTimestamp(), message,
                toUsage(snapshot.getUsage()), snapshot.getThinking(), snapshot.getMetadata());
    }

    /**
     * 还原会话累计用量。
     *
     * @param snapshot 累计用量快照，可为 {@code null}（按零用量处理）
     * @return 会话累计用量
     */
    static SessionUsage toSessionUsage(SessionUsageSnapshot snapshot) {
        if (snapshot == null) {
            return SessionUsage.EMPTY;
        }
        return new SessionUsage(snapshot.getPromptTokens(), snapshot.getCompletionTokens(),
                snapshot.getTotalTokens(), snapshot.getLlmCalls(),
                snapshot.getCacheReadTokens(), snapshot.getCacheWriteTokens());
    }

    /**
     * 投影单条消息。
     *
     * @param message 会话消息，不可为 {@code null}
     * @return 消息快照
     */
    private static SessionMessageSnapshot captureMessage(SessionMessage message) {
        LlmMessage body = message.getMessage();
        List<SessionToolCallSnapshot> toolCalls = new ArrayList<SessionToolCallSnapshot>();
        for (LlmToolCall toolCall : body.getToolCalls()) {
            toolCalls.add(new SessionToolCallSnapshot(toolCall.getIndex(), toolCall.getId(),
                    toolCall.getName(), toolCall.getArguments()));
        }
        return new SessionMessageSnapshot(message.getMessageId(), message.getTimestamp(), body.getRole(),
                body.getContent(), body.getToolCallId(), body.getName(), toolCalls,
                captureTokenUsage(message.getUsage()), message.getThinking(), message.getMetadata());
    }

    /**
     * 投影一次调用的 token 用量。
     *
     * @param usage 一次调用用量，可为 {@code null}
     * @return 用量快照，入参为 {@code null} 时返回 {@code null}
     */
    private static TokenUsageSnapshot captureTokenUsage(LlmUsage usage) {
        if (usage == null) {
            return null;
        }
        return new TokenUsageSnapshot(usage.getPromptTokens(), usage.getCompletionTokens(),
                usage.getTotalTokens(), usage.getCacheReadTokens(), usage.getCacheWriteTokens());
    }

    /**
     * 投影会话累计用量。
     *
     * @param usage 会话累计用量，不可为 {@code null}
     * @return 累计用量快照
     */
    private static SessionUsageSnapshot captureUsage(SessionUsage usage) {
        return new SessionUsageSnapshot(usage.getPromptTokens(), usage.getCompletionTokens(),
                usage.getTotalTokens(), usage.getLlmCalls(),
                usage.getCacheReadTokens(), usage.getCacheWriteTokens());
    }

    /**
     * 还原一次调用的 token 用量。
     *
     * @param snapshot 用量快照，可为 {@code null}
     * @return 一次调用用量，入参为 {@code null} 时返回 {@code null}
     */
    private static LlmUsage toUsage(TokenUsageSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        // 五个字段都可能缺失，缺失按 0 计：这是「读过一次但厂商没给全」的合理下限
        return new LlmUsage(orZero(snapshot.getPromptTokens()), orZero(snapshot.getCompletionTokens()),
                orZero(snapshot.getTotalTokens()), orZero(snapshot.getCacheReadTokens()),
                orZero(snapshot.getCacheWriteTokens()));
    }

    /**
     * 把可空计数按零处理。
     *
     * @param value 计数，可为 {@code null}
     * @return 非空计数
     */
    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
