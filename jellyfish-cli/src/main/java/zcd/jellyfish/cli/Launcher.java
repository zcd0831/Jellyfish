package zcd.jellyfish.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.cli.console.ConsoleIO;
import zcd.jellyfish.cli.di.JellyfishComponent;
import zcd.jellyfish.cli.mode.CliRunMode;
import zcd.jellyfish.cli.mode.RunMode;
import zcd.jellyfish.cli.mode.ServerRunMode;
import zcd.jellyfish.cli.mode.TuiRunMode;
import zcd.jellyfish.core.AgentHarness;

import java.util.Objects;
import java.util.Optional;

/**
 * 启动模式分发与生命周期宿主：把「选哪个模式」与「内核什么时候起停」这两件事收在一处。
 * <p>
 * 三种模式共享同一段生命周期（{@link AgentHarness#bootstrap()} → 保证当前会话 → 跑模式 →
 * {@link AgentHarness#shutdown()}），差别只在 {@link RunMode} 实现；因此子命令之外的一切
 * （会话准备、退出码、异常收敛）都在这里做一次，模式实现不必各自重复。
 * <p>
 * <b>两条刻意的取舍</b>：
 * <ol>
 *     <li><b>占位模式不启动内核</b>：{@code -tui} / {@code -server} 在进生命周期之前就返回
 *     {@link ExitCodes#NOT_IMPLEMENTED}，避免白起事件线程、插件扫描与 HTTP 客户端池；</li>
 *     <li><b>shutdown 双保险</b>：{@code addShutdownHook} 覆盖 Ctrl+C / {@code kill}，{@code finally}
 *     覆盖正常路径与异常路径。两侧都会调 {@link AgentHarness#shutdown()}，靠内核自身的幂等保证安全。</li>
 * </ol>
 * <b>为什么 Ctrl+C 是「整体退出」而不是「只取消当前回合」</b>：后者需要 {@code sun.misc.Signal}
 * 这类 JDK 内部 API（不被 sonar 接受）。进程退出时 {@code ReActLooper.close()} 会打断进行中的回合，
 * 行为可解释，因此本轮接受这个较粗的语义。
 *
 * @author zcd
 */
public final class Launcher {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

    /** 关闭钩子线程名。 */
    private static final String SHUTDOWN_HOOK_NAME = "jellyfish-shutdown";

    /** 应用级装配结果。 */
    private final JellyfishComponent component;

    /** 输出面板。 */
    private final ConsoleIO console;

    /**
     * 构造启动器。
     *
     * @param component 应用级 Dagger 组件，不可为 {@code null}
     * @param console   输出面板，不可为 {@code null}
     */
    public Launcher(JellyfishComponent component, ConsoleIO console) {
        this.component = Objects.requireNonNull(component, "component must not be null");
        this.console = Objects.requireNonNull(console, "console must not be null");
    }

    /**
     * 按启动参数执行对应模式并返回退出码。
     * <p>
     * 失败按<b>类别</b>返回不同退出码：内核没起成 {@link ExitCodes#STARTUP_ERROR}、
     * 参数不可满足（{@code --session} / {@code --agent} / {@code --model} 不存在）
     * {@link ExitCodes#USAGE_ERROR}、模式运行中抛出 {@link ExitCodes#RUNTIME_ERROR}。
     * 模式自报环境不满足（如 TUI 无可交互终端）也归 {@link ExitCodes#STARTUP_ERROR}——它发生在内核启动之前，
     * 性质是「启动条件不具备」，与「配置写错」属于同一类，脚本都应当直接放弃。
     * 无论哪一类，{@code finally} 都会收敛内核。
     *
     * @param options 启动参数，不可为 {@code null}
     * @return 退出码，取值见 {@link ExitCodes}
     */
    public int launch(StartupOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        RunMode mode = modeFor(options);
        if (!mode.isImplemented()) {
            return mode.run(options);
        }
        // 环境自检必须排在启动内核之前：不满足时白起插件扫描、事件线程与 HTTP 客户端池毫无意义。
        // 这一步只做判定，不产生任何需要回收的资源。
        Optional<String> problem = mode.checkEnvironment(options);
        if (problem.isPresent()) {
            LOG.error("环境不满足：{}", problem.get());
            console.writeErrLine("错误：" + problem.get());
            return ExitCodes.STARTUP_ERROR;
        }
        AgentHarness harness = component.agentHarness();
        Thread hook = new Thread(harness::shutdown, SHUTDOWN_HOOK_NAME);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            // 三类失败分开：内核没起成 3、参数不可满足 2、跑挂了 4。
            // 混在一起会让脚本分不清「配置错了」与「参数写错了」。
            try {
                harness.bootstrap();
            } catch (JellyfishException e) {
                LOG.error("启动失败：{}", e.getMessage(), e);
                console.writeErrLine("启动失败：" + e.getMessage());
                return ExitCodes.STARTUP_ERROR;
            }
            try {
                new SessionBootstrap(component.sessionManager(), component.modelManager(), component.agentManager())
                        .ensureCurrentSession(options);
            } catch (JellyfishException e) {
                // --session / --agent / --model 不存在：这是「参数不可满足」，不是启动失败；
                // 用户写错的参数不该在终端里刷一屏栈
                LOG.error("会话准备失败：{}", e.getMessage());
                console.writeErrLine("错误：" + e.getMessage());
                return ExitCodes.USAGE_ERROR;
            }
            try {
                return mode.run(options);
            } catch (JellyfishException e) {
                LOG.error("运行失败：{}", e.getMessage(), e);
                console.writeErrLine("运行失败：" + e.getMessage());
                return ExitCodes.RUNTIME_ERROR;
            }
        } finally {
            removeShutdownHook(hook);
            harness.shutdown();
        }
    }

    /**
     * 选择启动模式实现。
     *
     * @param options 启动参数
     * @return 模式实现，保证非 {@code null}
     */
    RunMode modeFor(StartupOptions options) {
        switch (options.getMode()) {
            case TUI:
                return new TuiRunMode(component.agentHarness(), component.commandManager(),
                        component.sessionManager(), component.modelManager(), component.extensionRegistry(),
                        component.eventChannel(), console);
            case SERVER:
                return new ServerRunMode(console);
            case CLI:
            default:
                return new CliRunMode(component.agentHarness(), component.commandManager(),
                        component.sessionManager(), console);
        }
    }

    /**
     * 摘掉关闭钩子：正常路径下 {@code finally} 已经收敛过，钩子留着只会在 JVM 退出时再跑一次。
     * <p>
     * JVM 已经在关闭中时摘钩子会抛 {@link IllegalStateException}，那是「本来就要退出」的正常情形，
     * 吞掉即可；其它异常同样不应阻断关闭流程。
     *
     * @param hook 关闭钩子
     */
    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            LOG.debug("JVM 正在关闭，无需摘除关闭钩子");
        } catch (RuntimeException e) {
            LOG.warn("关闭钩子摘除失败，继续关闭流程：{}", e.getMessage());
        }
    }
}
