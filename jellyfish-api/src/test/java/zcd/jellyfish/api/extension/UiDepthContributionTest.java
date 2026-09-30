package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.ui.UiEmphasis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §8 UI 深度三个扩展点的 api 侧单元测试：工具行渲染提示与快捷键绑定。
 *
 * @author zcd
 */
class UiDepthContributionTest {

    @Test
    void tool_render_hint_none_should_specify_nothing() {
        ToolRenderHint hint = ToolRenderHint.none();

        assertTrue(hint.isEmpty());
        assertNull(hint.getEmphasis());
        assertNull(hint.getCollapsedByDefault());
        assertNull(hint.getShowArguments());
    }

    @Test
    void tool_render_hint_of_should_carry_only_specified_fields() {
        ToolRenderHint hint = ToolRenderHint.of(UiEmphasis.ACCENT, null, Boolean.FALSE);

        assertFalse(hint.isEmpty());
        assertEquals(UiEmphasis.ACCENT, hint.getEmphasis());
        assertNull(hint.getCollapsedByDefault());
        assertEquals(Boolean.FALSE, hint.getShowArguments());
    }

    @Test
    void tool_render_hint_request_should_route_by_tool_name() {
        ToolDescriptor descriptor = new ToolDescriptor("heartbeat", "心跳");
        ToolRenderHintRequest request = new ToolRenderHintRequest("heartbeat", descriptor,
                RuntimeInfo.unknown());

        assertEquals("heartbeat", request.getRouteKey());
        assertEquals("heartbeat", request.getToolName());
        assertEquals(descriptor, request.getDescriptor());
        assertEquals(ToolRenderHint.class, request.getResultType());
        assertNull(request.getSessionId());
    }

    @Test
    void tool_render_hint_request_should_default_shell_to_unknown() {
        assertFalse(new ToolRenderHintRequest("t", null, null).getShell().isInteractive());
    }

    @Test
    void shortcut_key_should_normalize_case_and_whitespace() {
        assertEquals("ctrl+b", new ShortcutBinding("  CTRL+B ", "todo", null).getKey());
    }

    @Test
    void shortcut_key_should_reject_shapes_the_terminal_cannot_report() {
        // shift+enter / ctrl+1 这类在别人的终端上会静默失效，而那种问题没人查得出来
        for (String key : Arrays.asList("shift+a", "ctrl+1", "ctrl+ab", "a", "ctrl+", null, "alt+x")) {
            assertThrows(JellyfishException.class, () -> new ShortcutBinding(key, "todo", null), key);
        }
    }

    @Test
    void shortcut_should_reject_blank_command_name() {
        assertThrows(JellyfishException.class, () -> new ShortcutBinding("ctrl+b", "  ", null));
    }

    @Test
    void shortcut_should_recognize_reserved_keys() {
        assertTrue(ShortcutBinding.isReserved("ctrl+c"));
        assertTrue(ShortcutBinding.isReserved(" CTRL+S "));
        assertFalse(ShortcutBinding.isReserved("ctrl+b"));
        assertTrue(ShortcutBinding.RESERVED_KEYS.containsAll(
                Arrays.asList("ctrl+c", "ctrl+s", "ctrl+t", "ctrl+e", "ctrl+o")));
        assertThrows(UnsupportedOperationException.class, () -> ShortcutBinding.RESERVED_KEYS.add("ctrl+z"));
    }

    @Test
    void shortcut_validity_helper_should_not_throw() {
        assertTrue(ShortcutBinding.isValidKey("ctrl+z"));
        assertFalse(ShortcutBinding.isValidKey("ctrl+z+1"));
        assertFalse(ShortcutBinding.isValidKey(null));
    }

    @Test
    void shortcut_contribution_should_deduplicate_and_reject_duplicates() {
        assertTrue(ShortcutContribution.none().isEmpty());
        assertTrue(ShortcutContribution.of(null).isEmpty());

        List<ShortcutBinding> bindings = new ArrayList<ShortcutBinding>();
        bindings.add(new ShortcutBinding("ctrl+b", "todo", "看待办"));
        bindings.add(new ShortcutBinding("ctrl+d", "todo", null));
        ShortcutContribution contribution = ShortcutContribution.of(bindings);

        assertEquals(2, contribution.getBindings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> contribution.getBindings().add(new ShortcutBinding("ctrl+f", "todo", null)));
        // 同一个处理器返回两条同键是编程错误：两条都同 order，「谁生效」没有合理答案
        assertThrows(JellyfishException.class, () -> ShortcutContribution.of(Arrays.asList(
                new ShortcutBinding("ctrl+b", "todo", null),
                new ShortcutBinding("ctrl+b", "session", null))));
    }

    @Test
    void shortcut_request_should_expose_reserved_keys() {
        ShortcutContributionRequest request = new ShortcutContributionRequest(RuntimeInfo.unknown());

        assertEquals(ShortcutBinding.RESERVED_KEYS, request.getReservedKeys());
        assertNull(request.getRouteKey());
        assertEquals(ShortcutContribution.class, request.getResultType());
        assertThrows(UnsupportedOperationException.class, () -> request.getReservedKeys().add("ctrl+z"));
    }

    @Test
    void shortcut_request_should_default_shell_to_unknown() {
        assertFalse(new ShortcutContributionRequest(null).getShell().isInteractive());
    }

    @Test
    void contributions_should_expose_empty_immutable_collections() {
        ShortcutContribution contribution = ShortcutContribution.of(Collections.<ShortcutBinding>emptyList());

        assertTrue(contribution.isEmpty());
        assertEquals(Collections.emptyList(), contribution.getBindings());
    }
}
