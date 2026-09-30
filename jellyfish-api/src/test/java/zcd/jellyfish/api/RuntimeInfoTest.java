package zcd.jellyfish.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuntimeInfo} 的单元测试：锁住「三种外壳的取值」与「缺省是未知外壳」两条契约。
 * <p>
 * 最要紧的一条是 {@code supportsApproval} 的取值表——它是插件做降级决策的唯一依据，
 * 三种外壳各错一次就会让插件在错误的前提下决定注不注册能力。
 *
 * @author zcd
 */
@DisplayName("RuntimeInfo 运行时信息快照")
class RuntimeInfoTest {

    @Test
    void cli_should_have_no_ui_and_no_approval() {
        // When
        RuntimeInfo info = RuntimeInfo.cli(false);

        // Then：命令行没有审批者，因此「需要审批」的调用一律按拒绝处理，插件据此不必提供那类能力
        assertEquals(RuntimeInfo.Shell.CLI, info.getShell());
        assertFalse(info.hasUI());
        assertFalse(info.supportsApproval());
        assertFalse(info.isInteractive());
    }

    @Test
    void tui_should_have_ui_and_approval() {
        // When
        RuntimeInfo info = RuntimeInfo.tui(true);

        // Then
        assertEquals(RuntimeInfo.Shell.TUI, info.getShell());
        assertTrue(info.hasUI());
        assertTrue(info.supportsApproval());
        assertTrue(info.isInteractive());
    }

    @Test
    void server_should_have_approval_but_no_ui() {
        // When
        RuntimeInfo info = RuntimeInfo.server(false);

        // Then：审批走 HTTP 桥，因此「具备审批通道」为真；但这不表示此刻有客户端连着
        assertEquals(RuntimeInfo.Shell.SERVER, info.getShell());
        assertFalse(info.hasUI());
        assertTrue(info.supportsApproval());
        assertFalse(info.isInteractive());
    }

    @Test
    void interactive_should_be_independent_from_shell_kind() {
        // When：同一个外壳，一个挂了终端一个没有（例如输出被管道接走）
        RuntimeInfo piped = RuntimeInfo.cli(false);

        // Then
        assertEquals(RuntimeInfo.Shell.CLI, piped.getShell());
        assertFalse(piped.isInteractive());
        assertNotEquals(piped, RuntimeInfo.cli(true));
    }

    @Test
    void unknown_should_be_conservative_and_never_throw() {
        // When
        RuntimeInfo info = RuntimeInfo.unknown();

        // Then：缺省值刻意保守——说「没有审批通道」最多让插件少提供一个功能，
        // 说「有」却实际没有则会让它提供一堆走不通的能力
        assertEquals(RuntimeInfo.Shell.CLI, info.getShell());
        assertFalse(info.hasUI());
        assertFalse(info.supportsApproval());
        assertFalse(info.isInteractive());
        assertSame(RuntimeInfo.unknown(), info);
    }

    @Test
    void forShell_should_return_same_snapshot_as_shell_specific_factory() {
        // Then：装配根只传一个枚举，不该自己维护一份会与专有工厂分叉的映射
        assertEquals(RuntimeInfo.cli(true), RuntimeInfo.forShell(RuntimeInfo.Shell.CLI, true));
        assertEquals(RuntimeInfo.tui(true), RuntimeInfo.forShell(RuntimeInfo.Shell.TUI, true));
        assertEquals(RuntimeInfo.server(true), RuntimeInfo.forShell(RuntimeInfo.Shell.SERVER, true));
        assertEquals(RuntimeInfo.unknown(), RuntimeInfo.forShell(null, false));
    }

    @Test
    void equals_should_compare_every_field() {
        // Given
        RuntimeInfo base = RuntimeInfo.tui(true);

        // Then：相等性是按字段比的，不是按「同一个外壳」比的
        assertEquals(RuntimeInfo.tui(true), base);
        assertEquals(RuntimeInfo.tui(true).hashCode(), base.hashCode());
        assertNotEquals(RuntimeInfo.tui(false), base);
        assertNotEquals(RuntimeInfo.cli(true), base);
    }
}
