package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.terminal.Terminal;

/**
 * 轮次进行中关掉回显：终端不在 readLine 里，敲键会被 TTY 直接回显到正文中间。
 * ISIG 不动，Ctrl-C 仍然能到达信号处理器。
 */
final class EchoControl {

    private final Terminal terminal;

    EchoControl(Terminal terminal) {
        this.terminal = terminal;
    }

    /** 返回原属性以便恢复；终端不支持就返回 null。 */
    org.jline.terminal.Attributes off() {
        try {
            org.jline.terminal.Attributes original = terminal.getAttributes();
            org.jline.terminal.Attributes copy = new org.jline.terminal.Attributes(original);
            copy.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO, false);
            terminal.setAttributes(copy);
            return original;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    void back(org.jline.terminal.Attributes original) {
        if (original == null) {
            return;
        }
        try {
            terminal.setAttributes(original);
        } catch (RuntimeException ignored) {
            // 恢复失败交给终端自己兜底
        }
    }
}
