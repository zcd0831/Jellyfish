package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.PluginRepository;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.PluginDescriptor;
import io.github.zcd0831.jellyfish.application.shared.port.PluginRuntimePort;
import io.github.zcd0831.jellyfish.domain.action.plugin.PluginError;
import io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 插件的安装 / 授权 / 停用：**"安装"与"授权"分开**——装只落自己的 plugin.json，
 * 插件此时还没有任何效果；授权才让它进入世界（08-application §8.9.6）。
 */
public final class ManagePluginsUseCase {

    static final String TOOLS = "tools";
    public static final int HOST_API_VERSION = 1;

    private final PluginRepository manifests;
    private final PluginRuntimePort runtime;
    private final PluginAssembler assembler;
    private final CommitScope commitScope;
    private final Set<String> capabilities;

    public ManagePluginsUseCase(PluginRepository manifests, PluginRuntimePort runtime, PluginAssembler assembler,
                                CommitScope commitScope, Set<String> capabilities) {
        this.manifests = manifests;
        this.runtime = runtime;
        this.assembler = assembler;
        this.commitScope = commitScope;
        this.capabilities = capabilities;
    }

    public List<PluginManifest> list() {
        return manifests.list();
    }

    /** 装：只读 jar 事实 + 落意图，不装载、不生效（§8.6 要点 1）。 */
    public Result<Void> install(PluginId pluginId, boolean trusted) {
        PluginDescriptor descriptor = descriptorOf(pluginId);
        if (descriptor == null) {
            return Result.fail(UseCaseError.of(PluginError.INVALID_ID,
                    "目录名与 jar 内 Plugin-Id 不一致，或目录里没有 jar", pluginId));
        }
        if (descriptor.apiVersion() != HOST_API_VERSION) {
            return Result.fail(UseCaseError.of(PluginError.INCOMPATIBLE,
                    "插件要求 API " + descriptor.apiVersion() + "，宿主是 " + HOST_API_VERSION, pluginId));
        }
        List<String> conflicts = runtime.conflictsOf(pluginId);
        if (!conflicts.isEmpty()) {
            return Result.fail(UseCaseError.of(PluginError.DEPENDENCY_CONFLICT,
                    "插件重复打包了宿主已提供的依赖：" + conflicts, pluginId));
        }
        PluginManifest manifest = PluginManifest.create(pluginId, descriptor.version(),
                descriptor.apiVersion(), toolIds(pluginId), declaredCapabilities(pluginId), trusted,
                trusted ? new Date() : null);
        commitScope.globalWrite(() -> {
            commitScope.stageReplace(manifest);
            commitScope.commit();
        });
        return Result.ok(null);
    }

    /** 授权一项能力：勾选不能超出声明（聚合内守护）；同一端口至多一个启用者（§7.6 规则 3、4）。 */
    public Result<Void> grant(PluginId pluginId, String capability) {
        PluginManifest manifest = manifests.load(pluginId);
        if (manifest == null) {
            return Result.fail(UseCaseError.of(PluginError.NOT_FOUND, "插件未安装", pluginId));
        }
        if (!manifest.declaredCapabilities().contains(capability)) {
            return Result.fail(UseCaseError.of(PluginError.UNDECLARED, "插件未声明该能力", capability));
        }
        PluginManifest holder = holderOf(pluginId, capability);
        if (holder != null) {
            return Result.fail(UseCaseError.of(PluginError.PORT_CONFLICT,
                    "该端口已被插件 " + holder.id().getValue() + " 占用", capability));
        }
        manifest.grant(capability);
        save(manifest);
        assembler.assembleAll();
        return Result.ok(null);
    }

    public Result<Void> revoke(PluginId pluginId, String capability) {
        PluginManifest manifest = manifests.load(pluginId);
        if (manifest == null) {
            return Result.fail(UseCaseError.of(PluginError.NOT_FOUND, "插件未安装", pluginId));
        }
        manifest.revoke(capability);
        save(manifest);
        assembler.assembleAll();
        return Result.ok(null);
    }

    /**
     * 卸载 = 摘掉运行期（停 + 卸 jar）；**目录由用户自己删**（05-action §5.8.6 规则 1，
     * 与"删 skills/ 目录即淘汰"同构）。
     */
    public Result<Void> uninstall(PluginId pluginId) {
        if (manifests.load(pluginId) == null) {
            return Result.fail(UseCaseError.of(PluginError.NOT_FOUND, "插件未安装", pluginId));
        }
        assembler.disassemble(pluginId);
        return Result.ok(null);
    }

    private void save(PluginManifest manifest) {
        commitScope.globalWrite(() -> {
            commitScope.stageReplace(manifest);
            commitScope.commit();
        });
    }

    private PluginDescriptor descriptorOf(PluginId pluginId) {
        for (PluginDescriptor descriptor : runtime.discover()) {
            if (descriptor.id().equals(pluginId)) {
                return descriptor;
            }
        }
        return null;
    }

    private PluginManifest holderOf(PluginId pluginId, String capability) {
        for (PluginManifest other : manifests.list()) {
            if (!other.id().equals(pluginId) && other.isGranted(capability)) {
                return other;
            }
        }
        return null;
    }

    private Set<ToolId> toolIds(PluginId pluginId) {
        Set<ToolId> ids = new LinkedHashSet<ToolId>();
        for (ToolDefinition tool : runtime.toolsOf(pluginId)) {
            ids.add(tool.id());
        }
        return ids;
    }

    /** 声明能力 = 该插件真的实现了哪些可替换点（逐个探，装配层知道名字）。 */
    private Set<String> declaredCapabilities(PluginId pluginId) {
        Set<String> declared = new LinkedHashSet<String>();
        for (String capability : capabilities) {
            if (runtime.extension(pluginId, capability) != null) {
                declared.add(capability);
            }
        }
        if (!runtime.toolsOf(pluginId).isEmpty()) {
            declared.add(TOOLS);
        }
        return declared;
    }
}
