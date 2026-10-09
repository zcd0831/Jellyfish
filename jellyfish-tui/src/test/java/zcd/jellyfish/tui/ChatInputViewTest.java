package zcd.jellyfish.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatInputView} 硬件光标定位的单元测试。
 * <p>
 * <b>为什么这件事值得单独锁住</b>：框架的 {@code TextArea.renderWithCursor} 只反显光标格、不调
 * {@code Frame.setCursorPosition}，硬件光标会停在上一帧最后写入的那一格。纯文本输入看不出问题
 * （软件反显照常显示），但输入法的预编辑串画在硬件光标处——首屏时那里是状态栏末列，预编辑串从末列换行
 * 会把整屏顶上去、应用缓冲区随即错位。这些断言就是钉住「硬件光标必须落在输入光标那一格」。
 *
 * @author zcd
 */
@DisplayName("输入区硬件光标定位")
class ChatInputViewTest {

    /**
     * 把输入区渲染进一个无终端帧。
     *
     * @param view   输入区
     * @param width  可用列数
     * @param height 可用行数
     * @return 渲染后的帧
     */
    private static Frame render(ChatInputView view, int width, int height) {
        Buffer buffer = Buffer.empty(Rect.of(width, height));
        Frame frame = Frame.forTesting(buffer);
        view.render(frame, frame.area(), RenderContext.empty());
        return frame;
    }

    /**
     * 构造一个按键一律不截胡的输入区。
     *
     * @return 输入区
     */
    private static ChatInputView input() {
        return new ChatInputView(keys -> EventResult.UNHANDLED, text -> { });
    }

    /**
     * 取帧上的硬件光标位置。
     *
     * @param frame 帧
     * @return 光标位置，没有时为 {@code null}
     */
    private static Position cursorOf(Frame frame) {
        return frame.cursorPosition().orElse(null);
    }

    @Test
    @DisplayName("超长粘贴整段不插入并提示：截断会让用户以为粘全了")
    void paste_should_reject_oversized_text() {
        List<String> notices = new ArrayList<String>();
        ChatInputView view = new ChatInputView(keys -> EventResult.UNHANDLED, notices::add);

        view.handlePasteEvent(new PasteEvent(repeat("a", ChatInputView.MAX_PASTE_CHARS + 1)));

        assertEquals(0, view.text().length(), "整段都不该进来");
        assertEquals(1, notices.size(), "必须有一条提示：否则用户以为粘全了");
        assertTrue(notices.get(0).contains("过长"), notices.get(0));
    }

    @Test
    @DisplayName("上限之内的粘贴照常插入，含换行的多行文本不被压成一行")
    void paste_should_accept_within_limit() {
        List<String> notices = new ArrayList<String>();
        ChatInputView view = new ChatInputView(keys -> EventResult.UNHANDLED, notices::add);

        view.handlePasteEvent(new PasteEvent("你好\n世界"));

        assertEquals("你好\n世界", view.text());
        assertTrue(notices.isEmpty());
    }

    @Test
    @DisplayName("上限判的是插入后的总长：反复粘贴小段同样不能把输入框堆到失控")
    void paste_should_count_existing_text_against_the_limit() {
        List<String> notices = new ArrayList<String>();
        ChatInputView view = new ChatInputView(keys -> EventResult.UNHANDLED, notices::add);
        view.replaceText(repeat("a", ChatInputView.MAX_PASTE_CHARS));

        view.handlePasteEvent(new PasteEvent("b"));

        assertEquals(ChatInputView.MAX_PASTE_CHARS, view.text().length(), "已到上限，一个新字符都不该进来");
        assertEquals(1, notices.size());
    }

    /**
     * 重复一个字符若干次。
     *
     * @param text  字符
     * @param times 次数
     * @return 重复后的文本
     */
    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString();
    }

    @Test
    @DisplayName("空输入时光标落在输入区首格：留在上一帧最后一格会让 IME 画在状态栏上")
    void render_should_placeCursorAtFirstCell_when_inputEmpty() {
        Frame frame = render(input(), 20, 1);

        assertEquals(new Position(0, 0), cursorOf(frame));
    }

    @Test
    @DisplayName("回填文本要滤掉控制字符：它会被渲染到输入框，提交后还会进模型上下文")
    void replaceText_should_stripControlChars() {
        ChatInputView view = input();

        view.replaceText("rm -rf ~\u001b[2J");

        assertEquals("rm -rf ~[2J", view.text());
        assertFalse(view.text().indexOf('\u001b') >= 0);
    }

    @Test
    @DisplayName("回车与退格同样要被滤掉：它们能把已显示的一行原地改写")
    void replaceText_should_stripCarriageReturnAndBackspace() {
        ChatInputView view = input();

        view.replaceText("a\rb\bc");

        assertEquals("abc", view.text());
    }

    @Test
    @DisplayName("打字后硬件光标跟随：中文按显示宽度推进两列")
    void render_should_advanceCursor_byDisplayWidth() {
        ChatInputView view = input();
        view.handleKeyEvent(KeyEvent.ofChar('a'), true);
        view.handleKeyEvent(KeyEvent.ofChar('你'), true);
        view.handleKeyEvent(KeyEvent.ofChar('好'), true);

        Frame frame = render(view, 20, 1);

        assertEquals(new Position(5, 0), cursorOf(frame));
    }

    @Test
    @DisplayName("多行输入时光标落在第二行：行号要跟着换行走")
    void render_should_placeCursorOnSecondRow_when_inputHasNewline() {
        ChatInputView view = input();
        view.handleKeyEvent(KeyEvent.ofChar('a'), true);
        view.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER), true);
        view.handleKeyEvent(KeyEvent.ofChar('你'), true);

        Frame frame = render(view, 20, 3);

        assertEquals(new Position(2, 1), cursorOf(frame));
    }

    @Test
    @DisplayName("宽度为 0 时不定位：宁可不动，也不能把光标送到可视区之外")
    void render_should_leaveCursorUnset_when_areaEmpty() {
        Frame frame = render(input(), 0, 0);

        assertFalse(frame.cursorPosition().isPresent());
    }
}
