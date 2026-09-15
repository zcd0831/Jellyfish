package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellUsage} 的单元测试。
 * <p>
 * 用法说明追加在 {@code /help} 之后，是用户查键位时唯一的去处；键位一旦与 {@link InputKeyMapper}
 * 脱节，用户就照着错的方式操作。因此这里锁住「必须提到的键位与入口」，而不是逐字比整段文案——
 * 文案措辞可以改，关键线索不能丢。
 *
 * @author zcd
 */
@DisplayName("TUI 用法说明文案")
class ShellUsageTest {

    @Test
    @DisplayName("文案非空")
    void text_should_notBeBlank() {
        assertFalse(ShellUsage.text().trim().isEmpty());
    }

    @Test
    @DisplayName("必须提示反转键位：Ctrl+S 发送、Enter 换行")
    void text_should_mentionInvertedSendKey() {
        String text = ShellUsage.text();

        assertTrue(text.contains("Ctrl+S"));
        assertTrue(text.contains("发送"));
        assertTrue(text.contains("Enter"));
        assertTrue(text.contains("换行"));
    }

    @Test
    @DisplayName("必须提示中断与退出")
    void text_should_mentionCancelAndQuit() {
        String text = ShellUsage.text();

        assertTrue(text.contains("Esc"));
        assertTrue(text.contains("Ctrl+C"));
    }

    @Test
    @DisplayName("必须提示消息区滚动方式")
    void text_should_mentionScrollKeys() {
        String text = ShellUsage.text();

        assertTrue(text.contains("PageUp"));
        assertTrue(text.contains("PageDown"));
    }

    @Test
    @DisplayName("必须提示补全与插件面板入口")
    void text_should_mentionCompletionAndUiEntry() {
        String text = ShellUsage.text();

        assertTrue(text.contains("补全"));
        assertTrue(text.contains("/ui"));
    }
}
