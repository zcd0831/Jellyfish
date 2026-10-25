package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandlers;
import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.action.PluginAssembler;
import io.github.zcd0831.jellyfish.application.action.PortRoutes;
import io.github.zcd0831.jellyfish.application.action.port.ExternalToolPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.PluginRepository;
import io.github.zcd0831.jellyfish.application.planning.SessionRunner;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.PluginRuntimePort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.shared.port.TokenCounter;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import io.github.zcd0831.jellyfish.infrastructure.plugin.Pf4jPluginRuntime;

import javax.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件装配：端口路由表 + PF4J 运行时 + 装配器。规则出处：模块-应用层 §5/§10、D24-6。
 *
 * <p>容器概念仍只在本模块：业务代码只认端口，路由表是 {@code di} 的装配状态。
 */
@Module
public final class PluginModule {

    /** 可替换面：6 个端口 + 编排扩展点 + 工具处理器（D24-5）。 */
    static Map<String, Class<?>> capabilityTypes() {
        Map<String, Class<?>> types = new LinkedHashMap<String, Class<?>>();
        types.put("Clock", Clock.class);
        types.put("TokenCounter", TokenCounter.class);
        types.put("ModelInvocationPort", ModelInvocationPort.class);
        types.put("ExternalToolPort", ExternalToolPort.class);
        types.put("HumanInteractionPort", HumanInteractionPort.class);
        types.put("ProgressPort", ProgressPort.class);
        types.put("SessionRunner", SessionRunner.class);
        types.put("InternalToolHandler", InternalToolHandler.class);
        return types;
    }

    @Provides
    @Singleton
    PortRoutes portRoutes() {
        return new PortRoutes();
    }

    @Provides
    @Singleton
    PluginRuntimePort pluginRuntimePort(DataDirectory dir, JsonCodec codec) {
        return new Pf4jPluginRuntime(dir.plugins(), codec, capabilityTypes());
    }

    @Provides
    @Singleton
    PluginAssembler pluginAssembler(PluginRepository manifests, PluginRuntimePort runtime,
                                    InternalToolHandlers handlers, PortRoutes routes) {
        return new PluginAssembler(manifests, runtime, handlers, routes);
    }

    @Provides
    @Singleton
    ManagePluginsUseCase managePluginsUseCase(PluginRepository manifests, PluginRuntimePort runtime,
                                              PluginAssembler assembler, CommitScope commitScope) {
        return new ManagePluginsUseCase(manifests, runtime, assembler, commitScope,
                capabilityTypes().keySet());
    }
}
