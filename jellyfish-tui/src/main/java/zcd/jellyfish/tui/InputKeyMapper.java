package zcd.jellyfish.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 按键判定：把一次按键映射成 {@link InputAction}。
 * <p>
 * <b>为什么不用修饰键做换行／发送的区分</b>（实测结论，决定了 T5 的反转键位）：
 * TamboUI 的键盘解码器不解析任何修饰键编码，{@code Shift+Enter}（{@code ESC \r}）、
 * CSI-u（{@code ESC [13;2u}）、CSI-27（{@code ESC [27;2;13~}）三种编码一律落成
 * {@link KeyCode#UNKNOWN}，{@code hasShift()} / {@code hasAlt()} <b>永远是 false</b>；
 * 而裸 {@code \r} 与 {@code \n} 都解码成不带修饰符的 {@link KeyCode#ENTER}。
 * 所以「{@code Enter} 发送 + 修饰键换行」在<b>所有</b>终端上都不可实现。
 * <p>
 * <b>{@code Ctrl+字母} 的编码怪癖</b>：它们解码成 {@link KeyCode#CHAR} + {@code ctrl} 标记 + 字母码点
 * （实测 {@code Ctrl+C} 得到码点 {@code 99}、{@code Ctrl+S} 得到 {@code 115}），
 * 没有专用的 {@link KeyCode} 可用，只能自己比对码点。
 * <p>
 * <b>{@code Ctrl+S} 会不会被终端吞掉</b>：它是终端的 XOFF 流控字符，若 {@code IXON} 未关，
 * 按键到不了应用、输出还会被冻住。实测框架进入 raw 模式时已关掉 {@code IXON}，
 * 事件能正常送达，因此可以安全地用作发送键。
 *
 * @author zcd
 */
final class InputKeyMapper {

    private InputKeyMapper() {
    }

    /**
     * 判定一次按键属于哪个动作。
     *
     * @param key 按键事件，不可为 {@code null}
     * @return 动作，未命中任何外壳快捷键时返回 {@link InputAction#EDIT}
     */
    static InputAction map(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            return InputAction.CANCEL;
        }
        if (isCtrl(key, 's')) {
            return InputAction.SEND;
        }
        if (isCtrl(key, 'c')) {
            return InputAction.QUIT;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            return InputAction.PAGE_UP;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            return InputAction.PAGE_DOWN;
        }
        if (key.isKey(KeyCode.END)) {
            return InputAction.TO_BOTTOM;
        }
        return InputAction.EDIT;
    }

    /**
     * 判断按键是否为指定的 {@code Ctrl+字母}。
     * <p>
     * 不能只看 {@code hasCtrl()}，那会把 {@code Ctrl+方向键} 之类也当命中；
     * 也不直接用 {@code isCharIgnoreCase}，它对非 {@code CHAR} 类型按键的返回值没有保证。
     * 这里显式限定 {@link KeyCode#CHAR} 后再比码点。
     *
     * @param key    按键事件
     * @param letter 期望的小写字母
     * @return 命中返回 {@code true}
     */
    static boolean isCtrl(KeyEvent key, char letter) {
        return key.hasCtrl()
                && key.code() == KeyCode.CHAR
                && Character.toLowerCase(key.codePoint()) == letter;
    }
}
