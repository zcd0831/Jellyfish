package zcd.jellyfish.tui;

import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

import java.util.Optional;

/**
 * 滚轮判定：把一次鼠标事件映射成消息区滚动动作。
 * <p>
 * <b>为什么滚轮要单列一个判定类</b>：它是本外壳唯一的鼠标输入，而鼠标事件<b>不经</b>
 * {@link InputKeyMapper}（那只认 {@code KeyEvent}）。判定与 {@link InputKeyMapper} 同构——
 * 纯函数、只产出 {@link InputAction}，怎么执行交给 {@link TuiApp}。这样「滚轮一格是向上还是向下」
 * 这件事能在没有终端的情况下被断言。
 * <p>
 * <b>为什么非滚轮事件返回空</b>：滚轮事件的前置条件是 {@code TuiConfig.mouseCapture(true)}
 * （见 {@link TuiApp#configure()}），而捕获一旦开启，按下 / 抬起 / 拖动也会一并送达。
 * 那些事件同样会被路由到全局处理器，本类必须只认滚轮、其余原样报空——否则点击与拖动会被
 * 当成长按不放的滚动。判空之后怎么处理由调用方决定：外壳把它们吞掉，以保持焦点常驻输入框。
 *
 * @author zcd
 */
final class MouseScrollMapper {

    /**
     * 滚轮一格的滚动行数。
     * <p>
     * 取 3 而不是整页：滚轮的语义是「微调」，需要连续拨动才能快速翻越历史；
     * 要整页翻越的用户有 {@code PageUp} / {@code PageDown}，两者不必互相替代。
     */
    static final int WHEEL_STEP_ROWS = 3;

    private MouseScrollMapper() {
    }

    /**
     * 判定一次鼠标事件属于哪种滚动动作。
     *
     * @param event 鼠标事件，不可为 {@code null}
     * @return 滚动动作；不是滚轮事件时返回 {@link Optional#empty()}
     */
    static Optional<InputAction> map(MouseEvent event) {
        MouseEventKind kind = event.kind();
        if (kind == MouseEventKind.SCROLL_UP) {
            return Optional.of(InputAction.SCROLL_UP);
        }
        if (kind == MouseEventKind.SCROLL_DOWN) {
            return Optional.of(InputAction.SCROLL_DOWN);
        }
        // 左右滚动没有对应动作：消息区是单栏纯文本，横向没有可滚动的东西
        return Optional.empty();
    }
}
