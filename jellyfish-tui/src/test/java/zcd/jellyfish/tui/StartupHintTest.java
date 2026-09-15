package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StartupHint} 的单元测试。
 * <p>
 * 启动提示是用户进入界面后看到的唯一指引，键位一旦与 {@link InputKeyMapper} 脱节，用户就照着错的方式操作。
 * 因此这里锁住「必须提到的键位与入口」，而不是去逐字比整段文案——文案措辞可以改，关键线索不能丢。
 *
 * @author zcd
 */
@DisplayName("启动提示文案")
class StartupHintTest {

    @Test
    @DisplayName("文案非空且以助手口吻开场")
    void text_should_notBeBlank_and_greet() {
        String text = StartupHint.text();

        assertFalse(text.trim().isEmpty());
        assertTrue(text.contains("你好"));
    }

    @Test
    @DisplayName("必须提示反转键位：Ctrl+S 发送、Enter 换行")
    void text_should_mentionInvertedSendKey() {
        String text = StartupHint.text();

        assertTrue(text.contains("Ctrl+S"));
        assertTrue(text.contains("发送"));
        assertTrue(text.contains("Enter"));
        assertTrue(text.contains("换行"));
    }

    @Test
    @DisplayName("必须提示中断、退出与命令入口")
    void text_should_mentionCancelQuitAndCommandEntry() {
        String text = StartupHint.text();

        assertTrue(text.contains("Esc"));
        assertTrue(text.contains("Ctrl+C"));
        assertTrue(text.contains("/help"));
    }

    @Test
    @DisplayName("必须提示消息区滚动方式")
    void text_should_mentionScrollKeys() {
        String text = StartupHint.text();

        assertTrue(text.contains("PageUp"));
        assertTrue(text.contains("PageDown"));
    }
}
