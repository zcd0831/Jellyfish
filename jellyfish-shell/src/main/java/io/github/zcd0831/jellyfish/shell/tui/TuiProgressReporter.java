package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;

/**
 * 进度用状态行原地覆盖（\r），不整屏重绘；丢弃不影响业务（D12.1、D20-2）。
 */
public final class TuiProgressReporter implements ProgressPort {

    private final AnsiRenderer renderer;

    public TuiProgressReporter(AnsiRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public void emit(ProgressEvent event) {
        switch (event.kind()) {
            case SUMMARY_DONE:
            case STEP_FINISHED:
                renderer.status("");
                renderer.info("· " + event.message());
                break;
            default:
                renderer.status("· " + event.message());
        }
    }
}
