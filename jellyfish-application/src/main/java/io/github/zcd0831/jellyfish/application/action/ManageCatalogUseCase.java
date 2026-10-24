package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentSource;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;

/**
 * 目录维护：内置资产不可改、`builtin.` 前缀保留给内置。
 * 规则出处：模块-应用层 §4（ManageCatalogUseCase）、技术决策 M04。
 */
public final class ManageCatalogUseCase {

    private static final String BUILTIN_PREFIX = "builtin.";

    private final CatalogRepository catalog;

    public ManageCatalogUseCase(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public Result<Void> saveTool(ToolDefinition tool) {
        if (tool.id().getValue().startsWith(BUILTIN_PREFIX)) {
            return reserved(tool.id().getValue());
        }
        catalog.saveTool(tool);
        return Result.ok(null);
    }

    public Result<Void> saveAgent(AgentDefinition agent) {
        if (agent.id().getValue().startsWith(BUILTIN_PREFIX) || agent.source() == AgentSource.BUILT_IN) {
            return reserved(agent.id().getValue());
        }
        catalog.saveAgent(agent);
        return Result.ok(null);
    }

    public Result<Void> saveProvider(Provider provider) {
        if (provider.id().getValue().startsWith(BUILTIN_PREFIX)) {
            return reserved(provider.id().getValue());
        }
        catalog.saveProvider(provider);
        return Result.ok(null);
    }

    public Result<Void> saveModel(Model model) {
        if (model.id().getValue().startsWith(BUILTIN_PREFIX)) {
            return reserved(model.id().getValue());
        }
        catalog.saveModel(model);
        return Result.ok(null);
    }

    public Result<Void> saveSkill(SkillDefinition skill) {
        if (skill.id().getValue().startsWith(BUILTIN_PREFIX)) {
            return reserved(skill.id().getValue());
        }
        catalog.saveSkill(skill);
        return Result.ok(null);
    }

    private static Result<Void> reserved(String id) {
        return Result.fail(UseCaseError.of(io.github.zcd0831.jellyfish.domain.perception.session.SessionError
                .NOT_FOUND, "builtin. 前缀保留给内置资产：" + id, id));
    }
}
