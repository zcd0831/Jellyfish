package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.InputReferenceChoice;
import zcd.jellyfish.core.input.InputReferenceCompletion;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputReferenceCompletionState} 的单元测试：验证弹收、选中环回与接受后的替换。
 * <p>
 * 这几条规则最容易出错（记错了记号就会「按 Esc 之后又弹回来」或「接受文件后面板不消失」），
 * 因此全部钉成断言。
 *
 * @author zcd
 */
@DisplayName("InputReferenceCompletionState 引用补全状态")
class InputReferenceCompletionStateTest {

    /** 被测对象。 */
    private final InputReferenceCompletionState state = new InputReferenceCompletionState();

    @Test
    @DisplayName("未命中时不激活")
    void refresh_should_stayInactive_when_notPresent() {
        state.refresh("hello", InputReferenceCompletion.empty());

        assertFalse(state.isActive());
    }

    @Test
    @DisplayName("命中时激活并把选中项停在第一条")
    void refresh_should_activate_and_selectFirst() {
        state.refresh("看看 @s", completion(3, 5, choice("src/"), choice("stop.txt")));

        assertTrue(state.isActive());
        assertEquals(0, state.getSelectedIndex());
        assertEquals("src/", state.selected().getLabel());
    }

    @Test
    @DisplayName("上下移动应环回")
    void moveDown_should_wrapAround() {
        state.refresh("看看 @s", completion(3, 5, choice("a"), choice("b")));

        state.moveUp();
        assertEquals("b", state.selected().getLabel());
        state.moveDown();
        assertEquals("a", state.selected().getLabel());
    }

    @Test
    @DisplayName("接受目录候选时插入片段并保持面板打开")
    void accept_should_keepOpen_when_directory() {
        state.refresh("看看 @s", completion(3, 5, choice("src/")));

        String accepted = state.accept("看看 @s");

        assertEquals("看看 @src/", accepted);
        assertTrue(state.isActive(), "目录应继续往下钻，面板保持打开");
    }

    @Test
    @DisplayName("接受文件候选时插入片段并收起面板，且同一片段不再重开")
    void accept_should_close_and_remember_when_file() {
        state.refresh("看看 @a", completion(3, 5, choice("a.txt")));

        String accepted = state.accept("看看 @a");

        assertEquals("看看 @a.txt", accepted);
        assertFalse(state.isActive());

        // 片段没变：即使内核又给出候选，也不应重开
        state.refresh("看看 @a.txt", completion(3, 9, choice("a.txt")));
        assertFalse(state.isActive());
    }

    @Test
    @DisplayName("片段变化后允许重新弹开")
    void refresh_should_reopen_when_token_changes() {
        state.refresh("看看 @a", completion(3, 5, choice("a.txt")));
        state.accept("看看 @a");
        assertFalse(state.isActive());

        state.refresh("看看 @a ", InputReferenceCompletion.empty());

        state.refresh("看看 @b", completion(3, 5, choice("b.txt")));
        assertTrue(state.isActive());
    }

    @Test
    @DisplayName("dismiss 后同一片段不再重开，换片段才重开")
    void dismiss_should_remember_until_token_changes() {
        state.refresh("看看 @a", completion(3, 5, choice("a.txt")));

        state.dismiss();

        assertFalse(state.isActive());
        state.refresh("看看 @a", completion(3, 5, choice("a.txt")));
        assertFalse(state.isActive());
        state.refresh("看看 @ab", completion(3, 6, choice("ab.txt")));
        assertTrue(state.isActive());
    }

    @Test
    @DisplayName("无选中项时 accept 返回 null")
    void accept_should_returnNull_when_noSelection() {
        state.refresh("@", InputReferenceCompletion.of(0, 1, "@", Collections.<InputReferenceChoice>emptyList()));

        assertNull(state.accept("@"));
    }

    /**
     * 构造一次命中结果。
     *
     * @param start   替换起点
     * @param end     替换终点
     * @param choices 候选
     * @return 补全结果
     */
    private static InputReferenceCompletion completion(int start, int end, InputReferenceChoice... choices) {
        return InputReferenceCompletion.of(start, end, "@", Arrays.asList(choices));
    }

    /**
     * 构造一条候选。
     *
     * @param insertText 插入文本
     * @return 候选
     */
    private static InputReferenceChoice choice(String insertText) {
        return new InputReferenceChoice(insertText, insertText, null);
    }
}
