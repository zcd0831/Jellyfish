package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionHeader;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/**
 * 会话头：身份 + 冻结的快照。`Session.create` 与 `Session.rehydrate` 都只吃这一个对象，
 * 于是"开新会话"和"从盘上重建"用同一条装配路径，不会出现某一方忘了某个字段
 * （04-perception §4.5、§4.8.1 规则 7）。
 * <p>
 * `title` 只为 `rehydrate` 准备：新会话没有标题，取标题是应用层的事。
 */
public final class SessionHeader {

    private final SessionId id;
    private final SessionId parentSessionId;
    private final AgentId agentId;
    private final ModelId modelId;
    private final String title;
    private final long createdDate;
    private final SystemPromptSnapshot systemPromptSnapshot;

    private SessionHeader(Builder builder) {
        this.id = builder.id;
        this.parentSessionId = builder.parentSessionId;
        this.agentId = builder.agentId;
        this.modelId = builder.modelId;
        this.title = builder.title;
        this.createdDate = builder.createdDate;
        this.systemPromptSnapshot = builder.systemPromptSnapshot;
    }

    /** 必填的三项放在入口上：会话 id、所属代理、当前模型。 */
    public static Builder builder(SessionId id, AgentId agentId, ModelId modelId) {
        return new Builder(id, agentId, modelId);
    }

    public static final class Builder {

        private final SessionId id;
        private final AgentId agentId;
        private final ModelId modelId;
        private SessionId parentSessionId;
        private String title;
        private long createdDate;
        private SystemPromptSnapshot systemPromptSnapshot;

        private Builder(SessionId id, AgentId agentId, ModelId modelId) {
            if (id == null || agentId == null || modelId == null) {
                throw new IllegalArgumentException("会话 id / 代理 / 模型都不能为空");
            }
            this.id = id;
            this.agentId = agentId;
            this.modelId = modelId;
        }

        /** 子会话才有父；根会话留空。 */
        public Builder parentSessionId(SessionId value) {
            this.parentSessionId = value;
            return this;
        }

        /** 只有重建时才有标题；新会话由应用层取完标题再 `retitle`。 */
        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder createdDate(long value) {
            this.createdDate = value;
            return this;
        }

        public Builder systemPromptSnapshot(SystemPromptSnapshot value) {
            this.systemPromptSnapshot = value;
            return this;
        }

        public SessionHeader build() {
            return new SessionHeader(this);
        }
    }

    SessionId id() {
        return id;
    }

    SessionId parentSessionId() {
        return parentSessionId;
    }

    AgentId agentId() {
        return agentId;
    }

    ModelId modelId() {
        return modelId;
    }

    String title() {
        return title;
    }

    long createdDate() {
        return createdDate;
    }

    SystemPromptSnapshot systemPromptSnapshot() {
        return systemPromptSnapshot;
    }

}
