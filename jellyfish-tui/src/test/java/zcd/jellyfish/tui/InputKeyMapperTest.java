package zcd.jellyfish.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputKeyMapper} 的单元测试。
 * <p>
 * 重点锁住两件不直观、且靠读代码看不出来的事：
 * 「{@code Ctrl+字母} 解码成 {@code CHAR}+{@code ctrl}+码点」，
 * 以及「{@code Enter} 不归外壳管」（T5 反转键位）。
 *
 * @author zcd
 */
@DisplayName("InputKeyMapper 按键判定")
class InputKeyMapperTest {

    /**
     * 构造一个 {@code Ctrl+字母} 按键，形状与框架实测解码结果一致。
     *
     * @param letter 字母
     * @return 按键事件
     */
    private static KeyEvent ctrl(char letter) {
        return KeyEvent.ofChar(letter, KeyModifiers.CTRL);
    }

    @Test
    @DisplayName("Ctrl+S 应判定为发送")
    void map_should_returnSend_when_ctrlS() {
        assertEquals(InputAction.SEND, InputKeyMapper.map(ctrl('s')));
    }

    @Test
    @DisplayName("Ctrl+C 应判定为退出")
    void map_should_returnQuit_when_ctrlC() {
        assertEquals(InputAction.QUIT, InputKeyMapper.map(ctrl('c')));
    }

    @Test
    @DisplayName("Esc 应判定为中断")
    void map_should_returnCancel_when_escape() {
        assertEquals(InputAction.CANCEL, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.ESCAPE)));
    }

    @Test
    @DisplayName("Enter 应判定为编辑（换行），不归外壳管")
    void map_should_returnEdit_when_enter() {
        assertEquals(InputAction.EDIT, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    @DisplayName("Shift+Enter 与 Alt+Enter 也应判定为编辑，不得误判为发送")
    void map_should_returnEdit_when_modifiedEnter() {
        assertEquals(InputAction.EDIT, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT)));
        assertEquals(InputAction.EDIT, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.ALT)));
    }

    @Test
    @DisplayName("普通字符应判定为编辑")
    void map_should_returnEdit_when_plainChar() {
        assertEquals(InputAction.EDIT, InputKeyMapper.map(KeyEvent.ofChar('a')));
    }

    @Test
    @DisplayName("PageUp / PageDown 应判定为翻页")
    void map_should_returnPageActions_when_pageKeys() {
        assertEquals(InputAction.PAGE_UP, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.PAGE_UP)));
        assertEquals(InputAction.PAGE_DOWN, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.PAGE_DOWN)));
    }

    @Test
    @DisplayName("End 应判定为跳到末尾")
    void map_should_returnToBottom_when_end() {
        assertEquals(InputAction.TO_BOTTOM, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.END)));
    }

    @Test
    @DisplayName("其它控制键应判定为编辑，不被外壳误拦")
    void map_should_returnEdit_when_otherKeys() {
        assertEquals(InputAction.EDIT, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.UP)));
        assertEquals(InputAction.EDIT, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.BACKSPACE)));
        assertEquals(InputAction.EDIT, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.HOME)));
    }

    @Test
    @DisplayName("大小写不同的 Ctrl+字母应同样命中")
    void map_should_hit_when_ctrlLetterUpperCase() {
        assertEquals(InputAction.SEND, InputKeyMapper.map(ctrl('S')));
        assertEquals(InputAction.QUIT, InputKeyMapper.map(ctrl('C')));
    }

    @Test
    @DisplayName("其它 Ctrl+字母不应命中外壳快捷键")
    void map_should_notHit_when_otherCtrlLetters() {
        assertEquals(InputAction.EDIT, InputKeyMapper.map(ctrl('a')));
        assertEquals(InputAction.EDIT, InputKeyMapper.map(ctrl('z')));
    }

    @Test
    @DisplayName("无修饰键的同名字母不应命中")
    void map_should_notHit_when_noCtrl() {
        assertNotEquals(InputAction.SEND, InputKeyMapper.map(KeyEvent.ofChar('s')));
        assertNotEquals(InputAction.QUIT, InputKeyMapper.map(KeyEvent.ofChar('c')));
    }

    @Test
    @DisplayName("带 Ctrl 的非字符键不应命中")
    void map_should_notHit_when_ctrlNonCharKey() {
        assertEquals(InputAction.EDIT, InputKeyMapper.map(KeyEvent.ofKey(KeyCode.LEFT, KeyModifiers.CTRL)));
    }

    @Test
    @DisplayName("isCtrl 应只在 CHAR 类型且码点匹配时返回真")
    void isCtrl_should_matchOnlyWhenCharAndSameCodePoint() {
        assertTrue(InputKeyMapper.isCtrl(ctrl('s'), 's'));
        assertFalse(InputKeyMapper.isCtrl(ctrl('s'), 'c'));
        assertFalse(InputKeyMapper.isCtrl(KeyEvent.ofChar('s'), 's'));
        assertFalse(InputKeyMapper.isCtrl(KeyEvent.ofKey(KeyCode.END, KeyModifiers.CTRL), 'e'));
    }
}
