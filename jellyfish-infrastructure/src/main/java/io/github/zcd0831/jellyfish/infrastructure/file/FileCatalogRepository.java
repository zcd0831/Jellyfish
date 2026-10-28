package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.PluginRepository;
import io.github.zcd0831.jellyfish.application.shared.port.PluginRuntimePort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;

import java.util.List;

/**
 * 工具 / 代理 / 供应商 / 模型 / 技能的统一目录：jar 内置 + 磁盘用户资产。
 * 规则出处：09-infrastructure §9.4、§9.5。
 */
public final class FileCatalogRepository implements CatalogRepository {

    private final ToolCatalog tools;
    private final AgentCatalog agents;
    private final ProviderCatalog providers;
    private final ModelCatalog models;
    private final SkillCatalog skills;
    private final PluginRepository pluginManifests;
    private final PluginRuntimePort pluginRuntime;

    public FileCatalogRepository(DataDirectory dataDirectory, JsonCodec codec, PluginRepository pluginManifests,
                                 PluginRuntimePort pluginRuntime) {
        this.tools = new ToolCatalog(dataDirectory, codec);
        this.agents = new AgentCatalog(dataDirectory);
        this.providers = new ProviderCatalog(dataDirectory, codec);
        this.models = new ModelCatalog(dataDirectory, codec);
        this.skills = new SkillCatalog(dataDirectory);
        this.pluginManifests = pluginManifests;
        this.pluginRuntime = pluginRuntime;
    }

    /** 三个来源：jar 内置 + 磁盘用户资产 + **已授权 tools 能力的插件**。 */
    /**
     * 自带一套空的插件来源：测试与工具场景用；装配层用 4 参构造，
     * 以便与 {@code PluginAssembler} 共享**同一个**运行时实例。
     */
    public static FileCatalogRepository standalone(DataDirectory dataDirectory, JsonCodec codec) {
        return new FileCatalogRepository(dataDirectory, codec, new FilePluginRepository(dataDirectory, codec),
                new io.github.zcd0831.jellyfish.infrastructure.plugin.Pf4jPluginRuntime(
                        dataDirectory.plugins(), codec, java.util.Collections.<String, Class<?>>emptyMap()));
    }

    @Override
    public List<ToolDefinition> tools() {
        List<ToolDefinition> all = new java.util.ArrayList<ToolDefinition>(tools.all());
        for (io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest manifest : pluginManifests.list()) {
            if (manifest.isGranted("tools")) {
                all.addAll(pluginRuntime.toolsOf(manifest.id()));
            }
        }
        return all;
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

    @Override
    public int countActiveSkills() {
        int active = 0;
        for (SkillDefinition skill : skills.all()) {
            if (skill.isActive()) {
                active++;
            }
        }
        return active;
    }
}
