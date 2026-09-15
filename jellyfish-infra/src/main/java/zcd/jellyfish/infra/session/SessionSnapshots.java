package zcd.jellyfish.infra.session;

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
     *
     * @param session 会话运行态，不可为 {@code null}
     * @return 会话快照
     */
    public static SessionSnapshot capture(Session session) {
        List<SessionMessageSnapshot> messages = new ArrayList<SessionMessageSnapshot>(session.size());
        for (SessionMessage message : session.getMessages()) {
            messages.add(captureMessage(message));
        }
        return new SessionSnapshot(session.getSessionId(), session.getCreatedAt(), session.getUpdatedAt(),
                session.getTitle(), session.getAgentId(), session.getProvider(), session.getModel(),
                session.getPermissionMode(), messages, captureUsage(session.getUsage()));
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
                toUsage(snapshot.getUsage()));
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
                snapshot.getTotalTokens(), snapshot.getLlmCalls());
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
                captureTokenUsage(message.getUsage()));
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
                usage.getTotalTokens());
    }

    /**
     * 投影会话累计用量。
     *
     * @param usage 会话累计用量，不可为 {@code null}
     * @return 累计用量快照
     */
    private static SessionUsageSnapshot captureUsage(SessionUsage usage) {
        return new SessionUsageSnapshot(usage.getPromptTokens(), usage.getCompletionTokens(),
                usage.getTotalTokens(), usage.getLlmCalls());
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
        // 三个字段都可能缺失，缺失按 0 计：这是「读过一次但厂商没给全」的合理下限
        return new LlmUsage(orZero(snapshot.getPromptTokens()), orZero(snapshot.getCompletionTokens()),
                orZero(snapshot.getTotalTokens()));
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
