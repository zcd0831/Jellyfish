package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.PluginRepository;
import io.github.zcd0831.jellyfish.application.shared.port.PluginRuntimePort;
import io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件启停的唯一落点：装载/卸载 jar，把扩展对象注册进处理器注册表与端口路由表。
 * **只换实现，不动声明**——工具清单与 schema 仍随会话快照冻结（D24-9）。
 * 规则出处：模块-应用层 §5、§10，D24-6。
 */
public final class PluginAssembler {

    private static final String TOOLS = "tools";
    private static final String TOOL_HANDLER = "InternalToolHandler";

    private final PluginRepository manifests;
    private final PluginRuntimePort runtime;
    private final InternalToolHandlers handlers;
    private final PortRoutes routes;

    public PluginAssembler(PluginRepository manifests, PluginRuntimePort runtime,
                           InternalToolHandlers handlers, PortRoutes routes) {
        this.manifests = manifests;
        this.runtime = runtime;
        this.handlers = handlers;
        this.routes = routes;
    }

    /** 路由表是 (manifest, runtime) 的纯函数：整体重算最不容易错，也不必担心增量残留。 */
    public void assembleAll() {
        List<PluginManifest> all = manifests.list();
        unregisterTools(all);
        Map<String, Object> next = new LinkedHashMap<String, Object>();
        for (PluginManifest manifest : all) {
            runtime.start(manifest.id());
            if (manifest.isGranted(TOOLS)) {
                registerTools(manifest);
            }
            for (String capability : manifest.grantedCapabilities()) {
                if (TOOLS.equals(capability) || next.containsKey(capability)) {
                    continue;   // 同一端口至多一个启用者（§7.6 规则 4）
                }
                Object extension = runtime.extension(manifest.id(), capability);
                if (extension != null) {
                    next.put(capability, extension);
                }
            }
        }
        routes.replaceAll(next);
    }

    public void disassemble(PluginId pluginId) {
        runtime.stop(pluginId);
        runtime.unload(pluginId);
        assembleAll();
    }

    private void registerTools(PluginManifest manifest) {
        for (ToolDefinition tool : runtime.toolsOf(manifest.id())) {
            InternalToolHandler handler = (InternalToolHandler) runtime.extension(manifest.id(), TOOL_HANDLER);
            if (handler != null) {
                handlers.register(tool.source().getEndpointOrIdentifier(), handler);
            }
        }
    }

    private void unregisterTools(List<PluginManifest> all) {
        for (PluginManifest manifest : all) {
            for (ToolDefinition tool : runtime.toolsOf(manifest.id())) {
                handlers.unregister(tool.source().getEndpointOrIdentifier());
            }
        }
    }
}
