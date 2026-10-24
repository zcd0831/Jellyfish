package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;

import java.util.List;

/**
 * 工具 / 代理 / 供应商 / 模型 / 技能的统一目录：jar 内置 + 磁盘用户资产。
 * 规则出处：模块-基础设施层 §3、§4，技术决策 M04。
 */
public final class FileCatalogRepository implements CatalogRepository {

    private final ToolCatalog tools;
    private final AgentCatalog agents;
    private final ProviderCatalog providers;
    private final ModelCatalog models;
    private final SkillCatalog skills;

    public FileCatalogRepository(DataDirectory dataDirectory, JsonCodec codec) {
        this.tools = new ToolCatalog(dataDirectory, codec);
        this.agents = new AgentCatalog(dataDirectory);
        this.providers = new ProviderCatalog(dataDirectory, codec);
        this.models = new ModelCatalog(dataDirectory, codec);
        this.skills = new SkillCatalog(dataDirectory);
    }

    @Override
    public List<ToolDefinition> tools() {
        return tools.all();
    }

    @Override
    public List<AgentDefinition> agents() {
        return agents.all();
    }

    @Override
    public List<Provider> providers() {
        return providers.all();
    }

    @Override
    public List<Model> models() {
        return models.all();
    }

    @Override
    public List<SkillDefinition> skills() {
        return skills.all();
    }

    @Override
    public void saveTool(ToolDefinition tool) {
        tools.save(tool);
    }

    @Override
    public void saveAgent(AgentDefinition agent) {
        agents.save(agent);
    }

    @Override
    public void saveProvider(Provider provider) {
        providers.save(provider);
    }

    @Override
    public void saveModel(Model model) {
        models.save(model);
    }

    @Override
    public void saveSkill(SkillDefinition skill) {
        skills.save(skill);
    }
}
