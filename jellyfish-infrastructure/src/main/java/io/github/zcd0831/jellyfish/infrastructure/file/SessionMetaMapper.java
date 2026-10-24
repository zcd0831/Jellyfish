package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SkillSummarySnapshot;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptSnapshot;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;

import java.util.ArrayList;
import java.util.List;

/** 聚合 ↔ meta.json 的唯一映射点，避免仓储与提交作用域各写一份。 */
final class SessionMetaMapper {

    private SessionMetaMapper() {
    }

    static SessionMetaDto toDto(Session session) {
        SessionMetaDto dto = new SessionMetaDto();
        dto.id = session.id();
        dto.parentId = session.parentSessionId();
        dto.agentId = session.agentId();
        dto.modelId = session.activeModelId();
        dto.status = session.status();
        dto.title = session.title();
        dto.createdDate = session.createdDate();
        SystemPromptSnapshot snapshot = session.systemPromptSnapshot();
        if (snapshot != null) {
            dto.systemPrompt = new SessionMetaDto.SnapshotDto();
            dto.systemPrompt.renderedPrompt = snapshot.getRenderedPrompt();
            for (ToolSchemaSnapshot tool : snapshot.getToolList()) {
                SessionMetaDto.ToolDto toolDto = new SessionMetaDto.ToolDto();
                toolDto.name = tool.getName();
                toolDto.description = tool.getDescription();
                toolDto.parametersJsonSchema = tool.getParametersJsonSchema();
                toolDto.outputDescription = tool.getOutputDescription();
                dto.systemPrompt.tools.add(toolDto);
            }
            for (SkillSummarySnapshot skill : snapshot.getSkillList()) {
                SessionMetaDto.SkillDto skillDto = new SessionMetaDto.SkillDto();
                skillDto.name = skill.getName();
                skillDto.description = skill.getDescription();
                dto.systemPrompt.skills.add(skillDto);
            }
        }
        return dto;
    }

    static Session toDomain(SessionMetaDto dto, List<io.github.zcd0831.jellyfish.domain.perception.session.Record> records) {
        return Session.rehydrate(dto.id, dto.parentId, dto.agentId, dto.modelId, dto.status, dto.title,
                dto.createdDate, records, snapshot(dto.systemPrompt));
    }

    private static SystemPromptSnapshot snapshot(SessionMetaDto.SnapshotDto dto) {
        if (dto == null) {
            return null;
        }
        List<ToolSchemaSnapshot> tools = new ArrayList<ToolSchemaSnapshot>();
        for (SessionMetaDto.ToolDto tool : dto.tools) {
            tools.add(new ToolSchemaSnapshot(tool.name, tool.description, tool.parametersJsonSchema,
                    tool.outputDescription));
        }
        List<SkillSummarySnapshot> skills = new ArrayList<SkillSummarySnapshot>();
        for (SessionMetaDto.SkillDto skill : dto.skills) {
            skills.add(new SkillSummarySnapshot(skill.name, skill.description));
        }
        return new SystemPromptSnapshot(dto.renderedPrompt, tools, skills);
    }
}
