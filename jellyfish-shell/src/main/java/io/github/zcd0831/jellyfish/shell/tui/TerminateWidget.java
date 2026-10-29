package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.Widget;

/**
 * `Esc` = 终止本轮：唯一的终止入口（08-application §8.9.4.1）。
 * 行编辑时也能按，不必等模型调用返回。
 */
final class TerminateWidget implements Widget {

    private final TuiApp app;

    TerminateWidget(TuiApp app) {
        this.app = app;
    }

    @Override
    public boolean apply() {
        app.onTerminate();
        return true;
    }
}
