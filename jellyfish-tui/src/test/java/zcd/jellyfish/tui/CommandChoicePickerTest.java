package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandChoice;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandChoicePicker} 的单元测试。
 * <p>
 * 关注点：打开条件、默认选中「当前取值」、上下移动的循环边界与关闭后的状态清理。
 *
 * @author zcd
 */
@DisplayName("CommandChoicePicker 二级选择页状态")
class CommandChoicePickerTest {

    @Test
    @DisplayName("打开时应记录命令原文并默认选中当前取值")
    void open_should_activateAndSelectCurrent() {
        CommandChoicePicker picker = new CommandChoicePicker();

        picker.open("/agent", choices());

        assertTrue(picker.isActive());
        assertEquals("/agent", picker.getBaseCommand());
        assertEquals(3, picker.getChoices().size());
        assertEquals(1, picker.getSelectedIndex());
        assertEquals("writer", picker.selected().getValue());
    }

    @Test
    @DisplayName("没有当前取值时默认选中第一项")
    void open_should_selectFirst_whenNoCurrent() {
        CommandChoicePicker picker = new CommandChoicePicker();

        picker.open("/model", Arrays.asList(
                new CommandChoice("a/b", "a/b"), new CommandChoice("c/d", "c/d")));

        assertEquals(0, picker.getSelectedIndex());
        assertEquals("a/b", picker.selected().getValue());
    }

    @Test
    @DisplayName("候选为空时不打开，避免弹出空页面")
    void open_should_ignoreEmptyChoices() {
        CommandChoicePicker picker = new CommandChoicePicker();

        picker.open("/agent", Collections.<CommandChoice>emptyList());
        picker.open("/agent", null);

        assertFalse(picker.isActive());
        assertNull(picker.selected());
        assertEquals("", picker.getBaseCommand());
    }

    @Test
    @DisplayName("上下移动应首尾循环")
    void moveUpAndDown_should_wrapAround() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("/agent", choices());

        // 初始选中当前取值（下标 1）
        picker.moveDown();
        assertEquals(2, picker.getSelectedIndex());
        picker.moveDown();
        assertEquals(0, picker.getSelectedIndex());
        picker.moveUp();
        assertEquals(2, picker.getSelectedIndex());
    }

    @Test
    @DisplayName("关闭后应回到未激活并清空状态")
    void dismiss_should_clearState() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("/agent", choices());

        picker.dismiss();

        assertFalse(picker.isActive());
        assertTrue(picker.getChoices().isEmpty());
        assertEquals(0, picker.getSelectedIndex());
        assertNull(picker.selected());
        assertEquals("", picker.getBaseCommand());
    }

    /**
     * 构造含当前取值（第二项）的候选。
     *
     * @return 候选列表
     */
    private static java.util.List<CommandChoice> choices() {
        return Arrays.asList(
                new CommandChoice("coder", "coder", "写代码", false),
                new CommandChoice("writer", "writer", "写文档", true),
                new CommandChoice("planner", "planner", null, false));
    }
}
