package zcd.jellyfish.tui;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Overflow;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.KeyEventHandler;
import dev.tamboui.toolkit.event.MouseEventHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;

import java.util.Objects;

/**
 * 输入区：多行输入元素，自带键位分流。
 * <p>
 * <b>为什么不用框架现成的 {@code TextAreaElement}（实测结论，三条都堵死了）</b>：
 * <ol>
 *     <li>{@code EventRouter.addGlobalHandler} 排在聚焦元素<b>之后</b>。实测往输入框里打 {@code ab} 加回车，
 *     全局处理器只收到 {@code ESCAPE}，字符与 {@code Enter} 全被输入框吞掉；</li>
 *     <li>按键是<b>冒泡</b>的：内层元素先处理，没处理才往上冒。因此把输入框包在装饰器里当父级，
 *     只能收到没人要的键（实测装饰器同样只看到 {@code ESCAPE}），<b>父级无法抢先</b>；</li>
 *     <li>{@code StyledElement.onKeyEvent} 对 {@code TextAreaElement} 是死钩子——它重写
 *     {@code handleKeyEvent} 时<b>不调用 {@code super}</b>，直接进自己的按键表。</li>
 * </ol>
 * <b>而且就算能绕过，框架的键盘解码器也满足不了 T5 的键位（实测）</b>：
 * {@code Shift+Enter}（{@code ESC \r}）、CSI-u（{@code ESC [13;2u}）、CSI-27（{@code ESC [27;2;13~}）
 * 三种修饰键编码一律落成 {@code UNKNOWN}，{@code hasShift()} / {@code hasAlt()} <b>永远是 false</b>，
 * 而裸 {@code \r} 与 {@code \n} 都解码成不带修饰符的 {@code ENTER}。也就是说
 * 「{@code Enter} 发送 + 修饰键换行」在<b>所有</b>终端上都不可实现。
 * 因此 T5 采用<b>反转键位</b>：{@code Enter} 换行、{@code Ctrl+S} 发送，
 * 本轮自己实现输入元素，编辑原语全部复用 {@link TextAreaState}
 * （插入 / 删除 / 光标移动 / 滚动），只有「按键归谁」由我们决定。
 * <p>
 * <b>渲染为什么要自己调部件</b>：{@code Element} 的注册发生在渲染期（父级通过
 * {@code RenderContext.renderChild} 把子元素登记进注册表），按键路由据此直接派发。
 * 若这里再嵌一个 {@code TextAreaElement}，它会以同样的 id 后注册、覆盖掉本元素，
 * 按键就又回到它手里了。直接渲染 {@link TextArea} 部件就不存在第二个注册者。
 * <p>
 * <b>键位归属（T5，反转方案）</b>：先给外壳一次截胡机会（{@code Ctrl+S} 发送、{@code Esc} 中断、
 * {@code Ctrl+C} 退出、滚动键），没被截走才走编辑逻辑，因此 {@code Enter} 自然落到「插入换行」。
 * 这样「哪个键发送」由外壳一处决定，不必散落在元素里。
 *
 * @author zcd
 */
public final class ChatInputView implements Element {

    /** 元素标识：焦点按它寻址，改变它等于换掉焦点目标。 */
    static final String ELEMENT_ID = "jellyfish-chat-input";

    /** 输入框最小内容行数。 */
    static final int MIN_ROWS = 1;

    /** 输入框最大内容行数。 */
    static final int MAX_ROWS = 6;

    /** 边框占用的行数（上下各一）。 */
    static final int BORDER_ROWS = 2;

    /** 提示文案：同时充当键位说明，省掉一行专门的帮助。 */
    private static final String PLACEHOLDER =
            "\u8bf4\u70b9\u4ec0\u4e48\u2026\uff08Enter \u6362\u884c\u00b7Ctrl+S \u53d1\u9001\u00b7Esc \u4e2d\u65ad\uff09";

    /** 外壳的按键截胡处理器。 */
    private final KeyEventHandler shortcuts;

    /** 编辑状态：插入、删除、光标移动等原语都由它提供。 */
    private final TextAreaState state = new TextAreaState();

    /**
     * 构造输入区。
     *
     * @param shortcuts 外壳按键截胡处理器，不可为 {@code null}
     */
    public ChatInputView(KeyEventHandler shortcuts) {
        this.shortcuts = Objects.requireNonNull(shortcuts, "shortcuts must not be null");
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        TextArea widget = TextArea.builder()
                .placeholder(PLACEHOLDER)
                .overflow(Overflow.WRAP_WORD)
                .build();
        // 用带光标的渲染：没有光标的输入框在用户看来就是「没反应」
        widget.renderWithCursor(area, frame.buffer(), state, frame);
    }

    @Override
    public Constraint constraint() {
        return Constraint.length(innerRows());
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth, innerRows());
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public String id() {
        return ELEMENT_ID;
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        EventResult intercepted = shortcuts.handle(event);
        if (intercepted.isHandled()) {
            return intercepted;
        }
        return edit(event);
    }

    @Override
    public EventResult handlePasteEvent(PasteEvent event) {
        String text = event.text();
        if (text == null || text.isEmpty()) {
            return EventResult.UNHANDLED;
        }
        state.insert(text);
        return EventResult.HANDLED;
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        return EventResult.UNHANDLED;
    }

    @Override
    public KeyEventHandler keyEventHandler() {
        return null;
    }

    @Override
    public MouseEventHandler mouseEventHandler() {
        return null;
    }

    @Override
    public boolean isDraggable() {
        return false;
    }

    @Override
    public Rect renderedArea() {
        return Rect.ZERO;
    }

    /**
     * 取输入框内容占用的行数（1～6）。
     *
     * @return 行数
     */
    public int innerRows() {
        return Math.max(MIN_ROWS, Math.min(state.lineCount(), MAX_ROWS));
    }

    /**
     * 取输入框面板占用的总行数（含边框）。
     *
     * @return 行数
     */
    public int panelRows() {
        return innerRows() + BORDER_ROWS;
    }

    /**
     * 判断输入框当前是否为空。
     *
     * @return 空白返回 {@code true}
     */
    public boolean isBlank() {
        String text = state.text();
        return text == null || text.trim().isEmpty();
    }

    /**
     * 取出输入内容（不清空、不修剪）。
     * <p>
     * 供补全判定读取：它需要看原文里有没有空白／换行，因此这里不做 {@link #takeText()} 那样的修剪。
     *
     * @return 输入框原文，保证非 {@code null}
     */
    public String text() {
        String text = state.text();
        return text == null ? "" : text;
    }

    /**
     * 整体替换输入内容，并把光标移到末尾。
     * <p>
     * 供补全接受候选后回填：命令名可能比已输入的片段长，逐字符插入无法保证结果正确。
     *
     * @param text 新内容，不可为 {@code null}
     */
    public void replaceText(String text) {
        state.setText(text);
        state.moveCursorToEnd();
    }

    /**
     * 取出输入内容并清空输入框。
     *
     * @return 去除首尾空白后的内容，保证非 {@code null}
     */
    public String takeText() {
        String text = state.text();
        clear();
        return text == null ? "" : text.trim();
    }

    /**
     * 清空输入框。
     * <p>
     * 中断时<b>刻意不调用</b>本方法：用户按 {@code Esc} 的意图通常是「别再说了」而不是
     * 「清空我刚才写的」，把内容留着才能改一下再发。
     */
    public void clear() {
        state.clear();
        state.moveCursorToStart();
    }

    /**
     * 处理编辑类按键。
     *
     * @param key 按键事件
     * @return 处理结果
     */
    private EventResult edit(KeyEvent key) {
        KeyCode code = key.code();
        if (code == KeyCode.CHAR) {
            return insertChar(key);
        }
        switch (code) {
            case ENTER:
                // 反转键位（T5）：裸回车与 \n 都在这里落成换行，发送走 Ctrl+S（外壳截走）
                state.insert("\n");
                return EventResult.HANDLED;
            case BACKSPACE:
                state.deleteBackward();
                return EventResult.HANDLED;
            case DELETE:
                state.deleteForward();
                return EventResult.HANDLED;
            case LEFT:
                state.moveCursorLeft();
                return EventResult.HANDLED;
            case RIGHT:
                state.moveCursorRight();
                return EventResult.HANDLED;
            case UP:
                state.moveCursorUp();
                return EventResult.HANDLED;
            case DOWN:
                state.moveCursorDown();
                return EventResult.HANDLED;
            case HOME:
                state.moveCursorToLineStart();
                return EventResult.HANDLED;
            default:
                return EventResult.UNHANDLED;
        }
    }

    /**
     * 插入一个字符。
     * <p>
     * <b>带 Ctrl 的字符一律不插入</b>：{@code Ctrl+字母} 在这套框架里解码成
     * {@code CHAR} + {@code ctrl} 标记（实测 {@code Ctrl+C} 会得到码点 {@code 99}），
     * 若不加区分就会把 {@code Ctrl+C} 当成真的敲了一个 {@code c}。这里是兼底护栏——
     * 外壳截胡已经在前面挡掉已知的组合，但它挡不住将来新增的快捷键漏网。
     * <p>
     * 按码点而不是 {@code char} 插入：辅助平面字符（部分 Emoji）在 UTF-16 里是两个 {@code char}，
     * 逐 {@code char} 插入会把它们拆成两个非法码位。
     *
     * @param key 按键事件
     * @return 处理结果
     */
    private EventResult insertChar(KeyEvent key) {
        if (key.hasCtrl()) {
            return EventResult.UNHANDLED;
        }
        int codePoint = key.codePoint();
        if (codePoint <= 0) {
            return EventResult.UNHANDLED;
        }
        state.insert(new String(Character.toChars(codePoint)));
        return EventResult.HANDLED;
    }
}
