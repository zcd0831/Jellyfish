package io.github.zcd0831.jellyfish.domain.perception.session;

import java.util.List;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/** 会话聚合根。规则出处：模块-感知 §7.1、§3.1。 */
public class Session {

    private SessionId id;
    private SessionId parentSessionId;
    private AgentId agentId;
    private ModelId activeModelId;
    private SessionStatus status;
    private String title;
    private long createdDate;
    private List<Record> records;
    private SystemPromptSnapshot systemPromptSnapshot;

    public Session(SessionId id, SessionId parentSessionId, AgentId agentId, ModelId activeModelId, SessionStatus status, String title, long createdDate, List<Record> records, SystemPromptSnapshot systemPromptSnapshot) {
        this.id = id;
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.activeModelId = activeModelId;
        this.status = status;
        this.title = title;
        this.createdDate = createdDate;
        this.records = records;
        this.systemPromptSnapshot = systemPromptSnapshot;
    }

    public SessionId id() {
        return id;
    }

    public SessionId parentSessionId() {
        return parentSessionId;
    }

    public AgentId agentId() {
        return agentId;
    }

    public ModelId activeModelId() {
        return activeModelId;
    }

    public SessionStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public long createdDate() {
        return createdDate;
    }

    public List<Record> records() {
        return records;
    }

    public SystemPromptSnapshot systemPromptSnapshot() {
        return systemPromptSnapshot;
    }
}
