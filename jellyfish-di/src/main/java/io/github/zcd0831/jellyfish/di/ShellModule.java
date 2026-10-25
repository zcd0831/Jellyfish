package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.action.PortRoutes;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.infrastructure.model.HttpJsonClient;
import io.github.zcd0831.jellyfish.shell.tui.AnsiRenderer;
import io.github.zcd0831.jellyfish.shell.tui.OutputGateway;
import io.github.zcd0831.jellyfish.shell.tui.QuestionBroker;
import io.github.zcd0831.jellyfish.shell.tui.ShellCancellationToken;
import io.github.zcd0831.jellyfish.shell.tui.TuiApp;
import io.github.zcd0831.jellyfish.shell.tui.TuiHumanInteraction;
import io.github.zcd0831.jellyfish.shell.tui.TuiProgressReporter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintStream;
import javax.inject.Singleton;

/**
 * 外壳：终端的唯一持有者（D19）。人机交互与进度端口由这里实现；
 * 渲染、输入编排、输出单写者也都在这里（D20）。
 */
@Module
public final class ShellModule {

    private final PrintStream out;

    public ShellModule(PrintStream out) {
        this.out = out;
    }

    /** 终端归外壳所有（D19）；宽度与 TTY 能力都从它取，boot 不碰（D21-3）。 */
    @Provides
    @Singleton
    Terminal terminal() {
        try {
            return TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化终端", e);
        }
    }

    @Provides
    @Singleton
    OutputGateway outputGateway() {
        return new OutputGateway(out);
    }

    @Provides
    @Singleton
    AnsiRenderer ansiRenderer(OutputGateway gateway, Terminal terminal) {
        return new AnsiRenderer(gateway.sink(), terminal.getWidth());
    }

    @Provides
    @Singleton
    QuestionBroker questionBroker() {
        return new QuestionBroker();
    }

    @Provides
    @Singleton
    HumanInteractionPort humanInteractionPort(QuestionBroker broker, PortRoutes routes) {
        return routes.resolve("HumanInteractionPort", (HumanInteractionPort) new TuiHumanInteraction(broker));
    }

    @Provides
    @Singleton
    ProgressPort progressPort(AnsiRenderer renderer, PortRoutes routes) {
        return routes.resolve("ProgressPort", (ProgressPort) new TuiProgressReporter(renderer));
    }

    /** 唯一取消令牌：Ctrl-C 与 `/cancel` 都走它；令牌回调负责让在途 HTTP 读立刻失败（§8.4.1）。 */
    @Provides
    @Singleton
    ShellCancellationToken cancellationToken(final HttpJsonClient http) {
        ShellCancellationToken token = new ShellCancellationToken();
        token.onCancel(new Runnable() {
            @Override
            public void run() {
                http.cancelActive();
            }
        });
        return token;
    }

    @Provides
    @Singleton
    TuiApp tuiApp(StartSessionUseCase startSession, RunSessionUseCase runSession,
                  ManageSessionUseCase manageSession, AdjustPolicyUseCase adjustPolicy,
                  CatalogRepository catalog, AnsiRenderer renderer, QuestionBroker broker,
                  OutputGateway gateway, Terminal terminal, ShellCancellationToken token,
                  ManagePluginsUseCase plugins) {
        return new TuiApp(startSession, runSession, manageSession, adjustPolicy, catalog, renderer, broker,
                gateway, terminal, token, plugins);
    }
}
