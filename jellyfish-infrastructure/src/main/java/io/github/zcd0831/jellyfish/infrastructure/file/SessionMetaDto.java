package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.SessionStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/**
 * meta.json 的形状；与领域聚合分开，避免存储结构渗进模型。
 * 规则出处：模块-基础设施层 §4、技术决策 D07。
 */
public class SessionMetaDto {

    public SessionId id;
    public SessionId parentId;
    public AgentId agentId;
    public ModelId modelId;
    public SessionStatus status;
    public String title;
    public long createdDate;
}
