package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;

import java.io.PrintStream;

/**
 * 单向推送，丢弃不影响业务。属于外壳：终端只由外壳持有（D19）。规则出处：模块-基础设施层 §3、D14（进度与日志分开）。
 */
public final class TuiProgressReporter implements ProgressPort {

    private final PrintStream out;

    public TuiProgressReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public void emit(ProgressEvent event) {
        out.println("· " + event.message());
        out.flush();
    }
}
