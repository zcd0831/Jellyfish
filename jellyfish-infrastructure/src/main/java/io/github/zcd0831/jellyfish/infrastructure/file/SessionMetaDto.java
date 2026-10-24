package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.SessionStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;

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
    public SnapshotDto systemPrompt;

    /** 冻结快照必须落盘：续跑后仍要发同一份系统提示词与工具清单（感知 §7.1 规则 4）。 */
    public static class SnapshotDto {
        public String renderedPrompt;
        public List<ToolDto> tools = new ArrayList<ToolDto>();
        public List<SkillDto> skills = new ArrayList<SkillDto>();
    }

    public static class ToolDto {
        public String name;
        public String description;
        public String parametersJsonSchema;
        public String outputDescription;
    }

    public static class SkillDto {
        public String name;
        public String description;
    }
}
