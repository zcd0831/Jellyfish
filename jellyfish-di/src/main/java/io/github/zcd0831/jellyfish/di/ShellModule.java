package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.shell.tui.TuiHumanInteraction;
import io.github.zcd0831.jellyfish.shell.tui.TuiProgressReporter;

import java.io.InputStream;
import java.io.PrintStream;
import javax.inject.Singleton;

/**
 * 外壳：终端的唯一持有者。人机交互与进度端口由这里实现（D19）。
 */
@Module
public final class ShellModule {

    private final InputStream in;
    private final PrintStream out;

    public ShellModule(InputStream in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    @Provides
    @Singleton
    HumanInteractionPort humanInteractionPort() {
        return new TuiHumanInteraction(in, out);
    }

    @Provides
    @Singleton
    ProgressPort progressPort() {
        return new TuiProgressReporter(out);
    }
}
