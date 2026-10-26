package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.Collections;
import java.util.List;

/**
 * 会话回放的只读视图：会话头 + 最近若干条记录。
 * 只给界面看，不参与上下文组装（模型侧的窗口由 ContextWindowService 推导）。
 */
public final class SessionTranscript {

    private final SessionId sessionId;
    private final String title;
    private final ModelId modelId;
    private final long createdDate;
    private final List<Record> records;
    private final boolean hasEarlier;
    private final String contextLabel;

    public SessionTranscript(SessionId sessionId, String title, ModelId modelId, long createdDate,
                             List<Record> records, boolean hasEarlier, String contextLabel) {
        this.sessionId = sessionId;
        this.title = title;
        this.modelId = modelId;
        this.createdDate = createdDate;
        this.records = records;
        this.hasEarlier = hasEarlier;
        this.contextLabel = contextLabel;
    }

    public SessionId sessionId() {
        return sessionId;
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

    public List<Record> records() {
        return Collections.unmodifiableList(records);
    }

    /** 还有更早的记录没取出来（界面据此提示一句）。 */
    public boolean hasEarlier() {
        return hasEarlier;
    }

    /** "最近一次请求的用量 / 输入上限"：打开历史会话时提示符直接用，不必等下一轮。 */
    public String contextLabel() {
        return contextLabel;
    }
}
