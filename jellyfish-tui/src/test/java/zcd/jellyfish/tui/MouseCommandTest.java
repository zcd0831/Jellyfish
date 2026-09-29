package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MouseCommand} 的单元测试。
 * <p>
 * 锁住三件靠读代码容易看漏的事：无参数是「切换」而不是「开启」、取值非法时不改任何状态、
 * 以及两条提示都必须说清「还能不能滚」——提示是用户判断「滚轮是不是坏了」的唯一依据。
 *
 * @author zcd
 */
@DisplayName("MouseCommand 鼠标开关命令")
class MouseCommandTest {

    @Test
    @DisplayName("命令名常量应可直接用于补全清单（不含前缀）")
    void constants_should_beConsistent() {
        assertEquals("mouse", MouseCommand.NAME);
        assertEquals("/mouse", MouseCommand.PREFIX);
    }

    @Test
    @DisplayName("/mouse 及其带参数形式应命中，其它输入不命中")
    void isMouse_should_matchOnlyMouseCommand() {
        assertTrue(MouseCommand.isMouse("/mouse"));
        assertTrue(MouseCommand.isMouse("  /mouse  "));
        assertTrue(MouseCommand.isMouse("/mouse off"));
        assertTrue(MouseCommand.isMouse("/mouse on"));

        assertFalse(MouseCommand.isMouse("/mousex"));
        assertFalse(MouseCommand.isMouse("mouse"));
        assertFalse(MouseCommand.isMouse("/mous"));
        assertFalse(MouseCommand.isMouse("/ui"));
        assertFalse(MouseCommand.isMouse(null));
    }

    @Test
    @DisplayName("无参数时应取反当前状态，而不是固定开启")
    void targetOf_should_invert_when_noArgument() {
        assertEquals(Optional.of(Boolean.FALSE), MouseCommand.targetOf("/mouse", true));
        assertEquals(Optional.of(Boolean.TRUE), MouseCommand.targetOf("/mouse", false));
    }

    @Test
    @DisplayName("显式取值应以取值为准，且大小写不敏感")
    void targetOf_should_honorExplicitArgument() {
        assertEquals(Optional.of(Boolean.FALSE), MouseCommand.targetOf("/mouse off", true));
        assertEquals(Optional.of(Boolean.TRUE), MouseCommand.targetOf("/mouse on", false));
        assertEquals(Optional.of(Boolean.FALSE), MouseCommand.targetOf("/mouse OFF", true));
        assertEquals(Optional.of(Boolean.TRUE), MouseCommand.targetOf("/mouse  On", false));
    }

    @Test
    @DisplayName("取值非法或参数过多时应报空，由调用方按用法错误处理（不改终端状态）")
    void targetOf_should_returnEmpty_when_argumentInvalid() {
        assertFalse(MouseCommand.targetOf("/mouse maybe", true).isPresent());
        assertFalse(MouseCommand.targetOf("/mouse off extra", true).isPresent());
        assertFalse(MouseCommand.targetOf("/mouse 1", false).isPresent());
    }

    @Test
    @DisplayName("交还终端的提示必须同时说清「可以复制」与「滚轮停用、改用翻页键」")
    void notice_should_explainBothGainAndCost_when_captureOff() {
        String notice = MouseCommand.notice(false);

        assertTrue(notice.contains("复制"));
        assertTrue(notice.contains("滚轮"));
        assertTrue(notice.contains("PageUp"));
        assertTrue(notice.contains("/mouse"));
    }

    @Test
    @DisplayName("收回应用的提示必须说清滚轮恢复、本地选中需按修饰键")
    void notice_should_explainCaptureRestored_when_captureOn() {
        String notice = MouseCommand.notice(true);

        assertTrue(notice.contains("滚轮"));
        assertTrue(notice.contains("修饰键"));
        assertTrue(notice.contains("/mouse"));
    }

    @Test
    @DisplayName("两条提示不相同：否则切换后看不出到底切到哪一边")
    void notice_should_differBetweenStates() {
        assertNotEquals(MouseCommand.notice(true), MouseCommand.notice(false));
    }

    @Test
    @DisplayName("只有交还终端时状态栏才留标记：滚轮没反应时必须能看出原因")
    void statusMarker_should_onlyRender_when_captureOff() {
        assertEquals("", MouseCommand.statusMarker(true));
        assertTrue(MouseCommand.statusMarker(false).contains("Ctrl+O"));
    }

    @Test
    @DisplayName("用法说明应给出两种取值")
    void usageError_should_mentionBothArguments() {
        String usage = MouseCommand.usageError();

        assertTrue(usage.contains(MouseCommand.PREFIX));
        assertTrue(usage.contains("on"));
        assertTrue(usage.contains("off"));
    }
}
