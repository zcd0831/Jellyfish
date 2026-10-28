package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.PluginRepository;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.PluginDescriptor;
import io.github.zcd0831.jellyfish.application.shared.port.PluginRuntimePort;
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
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 安装与授权分开、四种拒绝。 */
class ManagePluginsUseCaseTest {

    private static final PluginId PLUGIN = PluginId.of("demo.echo");

    private final FakeRepository manifests = new FakeRepository();
    private final FakeRuntime runtime = new FakeRuntime();
    private final PluginAssembler assembler = org.mockito.Mockito.mock(PluginAssembler.class);
    private final CommitScope commitScope = new InlineCommitScope(manifests);

    private ManagePluginsUseCase useCase() {
        return new ManagePluginsUseCase(manifests, runtime, assembler, commitScope,
                new LinkedHashSet<String>(Arrays.asList("Clock", "SessionRunner")));
    }

    @Test
    void 目录名与PluginId不一致即拒绝() {
        runtime.descriptors.add(new PluginDescriptor(PluginId.of("other"), "1.0.0", 1, false));

        Result<Void> result = useCase().install(PLUGIN, true);

        assertFalse(result.isOk());
        assertEquals("PL_INVALID_ID", result.error().error().code());
    }

    @Test
    void apiVersion不符即拒绝() {
        runtime.descriptors.add(new PluginDescriptor(PLUGIN, "1.0.0", 9, false));

        Result<Void> result = useCase().install(PLUGIN, true);

        assertFalse(result.isOk());
        assertEquals("PL_INCOMPATIBLE", result.error().error().code());
    }

    @Test
    void 重复打包宿主依赖即拒绝() {
        runtime.descriptors.add(new PluginDescriptor(PLUGIN, "1.0.0", 1, false));
        runtime.conflicts.add("okhttp3/");

        Result<Void> result = useCase().install(PLUGIN, true);

        assertFalse(result.isOk());
        assertEquals("PL_DEPENDENCY_CONFLICT", result.error().error().code());
    }

    @Test
    void 安装只落意图且声明来自真实扩展() {
        runtime.descriptors.add(new PluginDescriptor(PLUGIN, "1.0.0", 1, false));
        runtime.extensions.put("Clock", new Object());
        runtime.tools.add(tool("echo"));

        assertTrue(useCase().install(PLUGIN, true).isOk());

        PluginManifest saved = manifests.load(PLUGIN);
        assertTrue(saved.isTrusted());
        assertEquals(new LinkedHashSet<String>(Arrays.asList("Clock", "tools")), saved.declaredCapabilities());
        assertTrue(saved.grantedCapabilities().isEmpty(), "装完还没有任何效果");
        org.mockito.Mockito.verify(assembler, org.mockito.Mockito.never()).assembleAll();
    }

    @Test
    void 授权超出声明即拒绝() {
        installWith("Clock");

        Result<Void> result = useCase().grant(PLUGIN, "TokenCounter");

        assertFalse(result.isOk());
        assertEquals("PL_UNDECLARED", result.error().error().code());
    }

    @Test
    void 同一端口已有启用者即冲突() {
        installWith("Clock");
        PluginManifest other = PluginManifest.create(PluginId.of("other"), "1.0.0", 1,
                Collections.<ToolId>emptySet(), new LinkedHashSet<String>(Arrays.asList("Clock")), false, null);
        other.grant("Clock");
        manifests.save(other);

        Result<Void> result = useCase().grant(PLUGIN, "Clock");

        assertFalse(result.isOk());
        assertEquals("PL_PORT_CONFLICT", result.error().error().code());
        assertTrue(result.error().detail().contains("other"));
    }

    @Test
    void 授权成功后落盘并触发装配() {
        installWith("Clock");

        assertTrue(useCase().grant(PLUGIN, "Clock").isOk());

        assertTrue(manifests.load(PLUGIN).isGranted("Clock"));
        org.mockito.Mockito.verify(assembler).assembleAll();
    }

    private void installWith(String... declared) {
        runtime.descriptors.add(new PluginDescriptor(PLUGIN, "1.0.0", 1, false));
        for (String capability : declared) {
            runtime.extensions.put(capability, new Object());
        }
        assertTrue(useCase().install(PLUGIN, true).isOk());
    }

    private static ToolDefinition tool(String id) {
        return new ToolDefinition(ToolId.of(id), id, "描述", new ToolSchema("{}"), new ToolSchema("{}"),
                new ToolSource(SourceType.PLUGIN, "demo.echo/" + id), SideEffectLevel.READ_ONLY, 1000,
                ToolStatus.ACTIVE);
    }

    private static final class FakeRepository implements PluginRepository {

        private final List<PluginManifest> saved = new ArrayList<PluginManifest>();

        @Override
        public List<PluginManifest> list() {
            return saved;
        }

        @Override
        public PluginManifest load(PluginId pluginId) {
            for (PluginManifest manifest : saved) {
                if (manifest.id().equals(pluginId)) {
                    return manifest;
                }
            }
            return null;
        }

        @Override
        public void save(PluginManifest manifest) {
            saved.remove(load(manifest.id()));
            saved.add(manifest);
        }
    }

    private static final class FakeRuntime implements PluginRuntimePort {

        private final List<PluginDescriptor> descriptors = new ArrayList<PluginDescriptor>();
        private final List<ToolDefinition> tools = new ArrayList<ToolDefinition>();
        private final java.util.Map<String, Object> extensions = new java.util.HashMap<String, Object>();
        private final List<String> conflicts = new ArrayList<String>();

        @Override
        public List<PluginDescriptor> discover() {
            return descriptors;
        }

        @Override
        public List<ToolDefinition> toolsOf(PluginId pluginId) {
            return tools;
        }

        @Override
        public void start(PluginId pluginId) {
        }

        @Override
        public void stop(PluginId pluginId) {
        }

        @Override
        public void unload(PluginId pluginId) {
        }

        @Override
        public Object extension(PluginId pluginId, String capability) {
            return extensions.get(capability);
        }

        @Override
        public List<String> conflictsOf(PluginId pluginId) {
            return conflicts;
        }
    }

    /** 等价于 FileCommitScope：替换聚合时落到对应仓储。 */
    private static final class InlineCommitScope implements CommitScope {

        private final PluginRepository manifests;

        InlineCommitScope(PluginRepository manifests) {
            this.manifests = manifests;
        }

        @Override
        public void stageFact(Object fact) {
        }

        @Override
        public void stageAppend(io.github.zcd0831.jellyfish.domain.shared.SessionId sessionId,
                                io.github.zcd0831.jellyfish.domain.perception.session.Record record) {
        }

        @Override
        public void stageReplace(Object aggregate) {
            if (aggregate instanceof PluginManifest) {
                manifests.save((PluginManifest) aggregate);
            }
        }

        @Override
        public void globalWrite(Runnable work) {
            work.run();
        }

        @Override
        public void commit() {
        }
    }
}
