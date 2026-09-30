package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuntimeInfoHolder} 与 {@link PluginContext#runtimeInfo()} 的单元测试：
 * 锁住「缺省是未知外壳」「外壳写入后插件读得到」「子上下文看到同一份」三条契约。
 *
 * @author zcd
 */
@DisplayName("RuntimeInfoHolder 运行时信息持有者")
class RuntimeInfoHolderTest {

    /** 真实同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(new TypeRegistry());

    /** 真实事件通道。 */
    private final EventChannel events = new EventChannel(EventChannelOptions.defaults(), new TypeRegistry());

    @Test
    void snapshot_should_default_to_unknown_shell() {
        // When：不经过外壳启动流程的用法（测试、嵌入式）
        RuntimeInfo info = new RuntimeInfoHolder().snapshot();

        // Then：给一个保守的缺省值，而不是 null 或「必须由调用方伪造一个外壳」
        assertEquals(RuntimeInfo.unknown(), info);
    }

    @Test
    void set_should_reject_null() {
        // When / Then
        assertThrows(NullPointerException.class, () -> new RuntimeInfoHolder().set(null));
    }

    @Test
    void set_should_replace_previous_snapshot() {
        // Given
        RuntimeInfoHolder holder = new RuntimeInfoHolder();

        // When
        holder.set(RuntimeInfo.tui(true));

        // Then
        assertEquals(RuntimeInfo.tui(true), holder.snapshot());
        assertSame(RuntimeInfo.Shell.TUI, holder.snapshot().getShell());
    }

    @Test
    void pluginContext_should_expose_written_snapshot() {
        // Given：装配根在 bootstrap 之前写入，插件在 start() 里就会读
        RuntimeInfoHolder holder = new RuntimeInfoHolder();
        holder.set(RuntimeInfo.server(false));
        PluginContext context = new PluginContextFactory(extensions, events, new TypeRegistry(), holder)
                .create(PluginDeclaration.of("plugin-a"));

        // When
        RuntimeInfo info = context.runtimeInfo();

        // Then
        assertTrue(info.supportsApproval());
        assertSame(RuntimeInfo.Shell.SERVER, info.getShell());
    }

    @Test
    void pluginContext_should_expose_unknown_when_not_assembled_by_factory() {
        // Given：走不依赖工厂的公开构造路径（测试与嵌入式集成）
        PluginContextImpl context = new PluginContextImpl(PluginDeclaration.of("plugin-a"), extensions, events);

        // When / Then
        assertEquals(RuntimeInfo.unknown(), context.runtimeInfo());
    }

    @Test
    void subContext_should_share_snapshot_with_parent() {
        // Given：外壳是进程级事实，子单元与父单元看到的必须一致
        RuntimeInfoHolder holder = new RuntimeInfoHolder();
        holder.set(RuntimeInfo.tui(true));
        PluginContext parent = new PluginContextFactory(extensions, events, new TypeRegistry(), holder)
                .create(PluginDeclaration.of("plugin-a"));

        // When：持有者在派生子上下文之后被改写
        PluginContext child = parent.subContext("jira");
        holder.set(RuntimeInfo.cli(false));

        // Then：子上下文读到的是最新值，而不是派生那一刻的快照
        assertEquals(RuntimeInfo.cli(false), child.runtimeInfo());
        assertEquals(parent.runtimeInfo(), child.runtimeInfo());
    }
}
