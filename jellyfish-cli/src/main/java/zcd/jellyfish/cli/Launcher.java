package zcd.jellyfish.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.cli.console.ConsoleIO;
import zcd.jellyfish.di.JellyfishComponent;
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
 * 三种模式共享同一段生命周期（环境自检 → 项目级配置的信任表态 → {@link AgentHarness#bootstrap()} →
 * 保证当前会话 → 跑模式 → {@link AgentHarness#shutdown()}），差别只在 {@link RunMode} 实现；
 * 因此子命令之外的一切（会话准备、退出码、异常收敛）都在这里做一次，模式实现不必各自重复。
 * <p>
 * <b>shutdown 双保险</b>：{@code addShutdownHook} 覆盖 Ctrl+C / {@code kill}，{@code finally}
 * 覆盖正常路径与异常路径。两侧都会调 {@link AgentHarness#shutdown()}，靠内核自身的幂等保证安全。
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
     * <b>归类看的是失败发生在哪一步，而不是异常属于哪个类</b>：每处都接 {@link RuntimeException}，
     * 否则别的运行时异常会穿透到进程入口，被当成「初始化失败」退 3。
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
        // 环境自检必须排在启动内核之前：不满足时白起插件扫描、事件线程与 HTTP 客户端池毫无意义。
        // 这一步只做判定，不产生任何需要回收的资源。
        Optional<String> problem = mode.checkEnvironment(options);
        if (problem.isPresent()) {
            LOG.error("环境不满足：{}", problem.get());
            console.writeErrLine("错误：" + problem.get());
            return ExitCodes.STARTUP_ERROR;
        }
        // 运行时信息必须在 bootstrap 之前写入：插件在 start() 里就会读它
        // （例如「没有审批通道就不注册需要写权限的工具」），晚一步插件拿到的就是缺省值。
        // 它只依赖「选了哪个模式」与「有没有终端」两件事，因此在环境自检通过后写一次就定下来了
        component.runtimeInfoHolder().set(
                RuntimeInfo.forShell(mode.shell(), System.console() != null));
        AgentHarness harness = component.agentHarness();
        // 项目级配置的信任表态必须排在 bootstrap 之前：配置就是在 bootstrap 里装载的，
        // 晚一步问等于问了也不生效。它也不依赖任何需要回收的资源
        new ProjectConfigTrustConsent(console, component.projectConfigTrust())
                .resolve(component.appConfig(), options, mode.shell() == RuntimeInfo.Shell.TUI);
        Thread hook = new Thread(harness::shutdown, SHUTDOWN_HOOK_NAME);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            // 三类失败分开：内核没起成 3、参数不可满足 2、跑挂了 4。
            // 混在一起会让脚本分不清「配置错了」与「参数写错了」。
            //
            // 三处都按类别而不是按异常类型归类（都接 RuntimeException，不只 JellyfishException）：
            // 只认 JellyfishException 的话，别的运行时异常会一路穿透到进程入口，被当成「初始化失败」
            // 退 3——而那时内核往往已经起来了，脚本会据此去改一份本来没问题的配置。
            try {
                harness.bootstrap();
            } catch (RuntimeException e) {
                // 启动期抛出的任何异常都属于「内核没起成」：这一步跑的是配置加载与插件运行时
                LOG.error("启动失败：{}", messageOf(e), e);
                console.writeErrLine("启动失败：" + messageOf(e));
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
            } catch (RuntimeException e) {
                // 参数本身没问题却仍然出错：内核已经起来了，这是运行期故障（4），不是用法错误
                LOG.error("会话准备失败：{}", messageOf(e), e);
                console.writeErrLine("运行失败：" + messageOf(e));
                return ExitCodes.RUNTIME_ERROR;
            }
            try {
                return mode.run(options);
            } catch (RuntimeException e) {
                LOG.error("运行失败：{}", messageOf(e), e);
                console.writeErrLine("运行失败：" + messageOf(e));
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
                return new TuiRunMode(component.conversationService(), component.turnRegistry(),
                        component.shellStreams(),
                        component.commandManager(),
                        component.sessionManager(), component.modelManager(), component.agentManager(),
                        component.extensionRegistry(), component.eventChannel(), component.runtimeInfoHolder(),
                        component.approvalChannel(), component.askChannel(),
                        component.conversationCompactor(), component.inputDirectives(),
                        console, component.sessionDefaults());
            case SERVER:
                return new ServerRunMode(component.conversationService(), component.commandManager(),
                        component.sessionManager(), component.modelManager(), component.agentManager(),
                        component.approvalChannel(), component.askChannel(), component.healthCheck(),
                        component.turnRegistry(),
                        component.shellStreams(), component.runEventBus(), console);
            case CLI:
            default:
                return new CliRunMode(component.conversationService(), component.shellStreams(),
                        component.sessionManager(), console);
        }
    }

    /**
     * 取异常的可读描述。
     * <p>
     * 归到哪个类别不看异常类型，因此错误文案不能直接取 {@code getMessage()}——那样遇到一个
     * 没带描述的异常就会打出一句空白提示。取不到描述时退到类名，至少说明它是哪一类故障。
     *
     * @param error 异常，保证非 {@code null}
     * @return 错误描述，保证非空
     */
    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
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
