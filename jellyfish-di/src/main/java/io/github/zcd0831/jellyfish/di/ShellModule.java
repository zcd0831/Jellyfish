package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.infrastructure.model.HttpJsonClient;
import io.github.zcd0831.jellyfish.shell.tui.AnsiRenderer;
import io.github.zcd0831.jellyfish.shell.tui.QuestionBroker;
import io.github.zcd0831.jellyfish.shell.tui.TuiApp;
import io.github.zcd0831.jellyfish.shell.tui.TuiHumanInteraction;
import io.github.zcd0831.jellyfish.shell.tui.TuiProgressReporter;

import java.io.PrintStream;
import javax.inject.Singleton;

/**
 * 外壳：终端的唯一持有者（D19）。人机交互与进度端口由这里实现；
 * 渲染与输入编排也在这里（D20）。
 */
@Module
public final class ShellModule {

    private final PrintStream out;
    private final int width;

    public ShellModule(PrintStream out, int width) {
        this.out = out;
        this.width = width;
    }

    @Provides
    @Singleton
    AnsiRenderer ansiRenderer() {
        return new AnsiRenderer(out, width);
    }

    @Provides
    @Singleton
    QuestionBroker questionBroker(AnsiRenderer renderer) {
        return new QuestionBroker(renderer);
    }

    @Provides
    @Singleton
    HumanInteractionPort humanInteractionPort(QuestionBroker broker) {
        return new TuiHumanInteraction(broker);
    }

    @Provides
    @Singleton
    ProgressPort progressPort(AnsiRenderer renderer) {
        return new TuiProgressReporter(renderer);
    }

    @Provides
    @Singleton
    TuiApp tuiApp(StartSessionUseCase startSession, RunSessionUseCase runSession,
                  ManageSessionUseCase manageSession, AdjustPolicyUseCase adjustPolicy,
                  CatalogRepository catalog, AnsiRenderer renderer, QuestionBroker broker,
                  final HttpJsonClient http) {
        return new TuiApp(startSession, runSession, manageSession, adjustPolicy, catalog, renderer, broker,
                new Runnable() {
                    @Override
                    public void run() {
                        http.cancelActive();
                    }
                });
    }
}
