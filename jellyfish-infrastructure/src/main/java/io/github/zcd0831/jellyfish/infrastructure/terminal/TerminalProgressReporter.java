package io.github.zcd0831.jellyfish.infrastructure.terminal;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;

import java.io.PrintStream;

/**
 * 单向推送，丢弃不影响业务。规则出处：模块-基础设施层 §3、D14（进度与日志分开）。
 */
public final class TerminalProgressReporter implements ProgressPort {

    private final PrintStream out;

    public TerminalProgressReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public void emit(ProgressEvent event) {
        out.println("· " + event.message());
        out.flush();
    }
}
