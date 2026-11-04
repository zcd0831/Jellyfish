package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.PluginRepository;
import io.github.zcd0831.jellyfish.application.shared.port.PluginRuntimePort;
import io.github.zcd0831.jellyfish.application.shared.port.PluginDescriptor;
import io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest;
import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSchema;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSource;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolStatus;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件装配：只有已授权的才装载；启动装配整链由 Main 调一次。
 * 规则出处：08-application §8.9.6。
 */
class PluginAssemblerTest {

    private final List<PluginManifest> manifests = new ArrayList<PluginManifest>();
    private final FakeRuntime runtime = new FakeRuntime();
    private final InternalToolHandlers handlers = InternalToolHandlers.empty();
    private final PortRoutes routes = new PortRoutes();

    private PluginAssembler assembler() {
        return new PluginAssembler(new FakeRepository(manifests), runtime, handlers, routes);
    }

    @Test
    void 未授权的插件不装载() {
        manifests.add(manifest("demo.echo", false));

        assembler().assembleAll();

        // 未授权的第三方代码不得开始执行
        assertTrue(runtime.started.isEmpty(), "未授权插件不能被 start：" + runtime.started);
    }

    @Test
    void 已授权的插件装载并注册工具处理器() {
        manifests.add(manifest("demo.echo", true));

        assembler().assembleAll();

        assertEquals(Collections.singletonList(PluginId.of("demo.echo")), runtime.started);
        assertTrue(handlers.find("plugin.demo.echo.run") != null, "已授权插件的工具要能调到");
    }

    @Test
    void 已授权但不带工具能力的插件只注册端口路由() {
        PluginManifest manifest = PluginManifest.create(PluginId.of("demo.port"), "1.0.0", 1,
                Collections.<ToolId>emptySet(),
                new LinkedHashSet<String>(Arrays.asList("Clock", "SessionRunner")),
                true, new Date());
        manifest.grant("Clock");
        manifests.add(manifest);

        assembler().assembleAll();

        assertEquals(Collections.singletonList(PluginId.of("demo.port")), runtime.started);
        assertTrue(routes.snapshot().containsKey("Clock"), routes.snapshot().toString());
        assertNull(routes.snapshot().get("SessionRunner"), "未授权的能力不能进路由表");
    }

    private static PluginManifest manifest(String id, boolean granted) {
        PluginManifest manifest = PluginManifest.create(PluginId.of(id), "1.0.0", 1,
                new LinkedHashSet<ToolId>(Arrays.asList(ToolId.of("plugin." + id + ".run"))),
                new LinkedHashSet<String>(Arrays.asList("tools")), true, new Date());
        if (granted) {
            manifest.grant("tools");
        }
        return manifest;
    }

    private static final class FakeRepository implements PluginRepository {

        private final List<PluginManifest> all;

        FakeRepository(List<PluginManifest> all) {
            this.all = all;
        }

        @Override
        public List<PluginManifest> list() {
            return new ArrayList<PluginManifest>(all);
        }

        @Override
        public PluginManifest load(PluginId pluginId) {
            for (PluginManifest manifest : all) {
                if (manifest.id().equals(pluginId)) {
                    return manifest;
                }
            }
            return null;
        }

        @Override
        public void save(PluginManifest manifest) {
            all.add(manifest);
        }
    }

    private static final class FakeRuntime implements PluginRuntimePort {

        private final List<PluginId> started = new ArrayList<PluginId>();

        @Override
        public List<PluginDescriptor> discover() {
            return Collections.emptyList();
        }

        @Override
        public void start(PluginId pluginId) {
            started.add(pluginId);
        }

        @Override
        public void stop(PluginId pluginId) {
        }

        @Override
        public void unload(PluginId pluginId) {
        }

        @Override
        public List<ToolDefinition> toolsOf(PluginId pluginId) {
            return Collections.singletonList(new ToolDefinition(
                    ToolId.of("plugin." + pluginId.getValue() + ".run"),
                    "plugin." + pluginId.getValue() + ".run", "插件工具",
                    new ToolSchema("{\"type\":\"object\"}"), null,
                    new ToolSource(SourceType.PLUGIN, "plugin." + pluginId.getValue() + ".run"),
                    SideEffectLevel.REPEATABLE, 1000, ToolStatus.ACTIVE));
        }

        @Override
        public Object extension(PluginId pluginId, String capability) {
            if ("tools".equals(capability) || "InternalToolHandler".equals(capability)) {
                return new InternalToolHandler() {
                    @Override
                    public ToolExecOutcome handle(io.github.zcd0831.jellyfish.domain.perception.session.Session s,
                                                  Map<String, Object> args) {
                        return ToolExecOutcome.plain("ok");
                    }
                };
            }
            return capability;
        }

        @Override
        public List<String> conflictsOf(PluginId pluginId) {
            return Collections.emptyList();
        }
    }
}
