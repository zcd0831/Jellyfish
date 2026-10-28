package io.github.zcd0831.jellyfish.application.perception.port;

import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/** 会话列表里的一行：只够"让人挑一个"，不载入记录流水。 */
public final class SessionSummary {

    private final SessionId id;
    private final SessionId parentId;
    private final String title;
    private final ModelId modelId;
    private final long createdDate;

    public SessionSummary(SessionId id, SessionId parentId, String title, ModelId modelId, long createdDate) {
        this.id = id;
        this.parentId = parentId;
        this.title = title;
        this.modelId = modelId;
        this.createdDate = createdDate;
    }

    public SessionId id() {
        return id;
    }

    public SessionId parentId() {
        return parentId;
    }

    public String title() {
        return title;
    }

    public ModelId modelId() {
        return modelId;
    }

    public long createdDate() {
        return createdDate;
    }

    public boolean isSubSession() {
        return parentId != null;
    }
}
