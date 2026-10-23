package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import java.util.List;

/**
 * 工具 / 代理 / 供应商 / 模型 / 技能的统一目录。规则出处：模块-基础设施层 §3。
 */
public interface CatalogRepository {

    List<ToolDefinition> tools();

    List<AgentDefinition> agents();

    List<Provider> providers();

    List<Model> models();

    List<SkillDefinition> skills();

    void saveTool(ToolDefinition tool);

    void saveAgent(AgentDefinition agent);

    void saveProvider(Provider provider);

    void saveModel(Model model);

    void saveSkill(SkillDefinition skill);
}
