package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/**
 * 工具结果全文落本地文件，返回 resourceUri。规则出处：模块-行动 §7.1 规则 6。
 */
public interface ArtifactStore {

    String put(SessionId sessionId, RecordId recordId, byte[] content);

    void deleteSession(SessionId sessionId);
}
