package zcd.jellyfish.cli.mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.cli.ExitCodes;
import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.ConsoleIO;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.tui.TuiApp;
import zcd.jellyfish.tui.TuiTerminal;

import java.util.Objects;
import java.util.Optional;

/**
 * TUI 模式：交互式终端界面，用 TamboUI 构建。
 * <p>
 * <b>本类只做接线</b>：三个内核门面（{@code AgentHarness} / {@code CommandManager} / {@code SessionManager}）
 * 加一个模型门面交给 {@link TuiApp}，然后把异常收敛成退出码。界面、投影、滚动、按键全在
 * {@code jellyfish-tui} 里——这样 {@code Launcher} 与启动参数解析一行不用改，
 * 与 {@code CliRunMode} 保持对称。
 * <p>
 * <b>为什么不把本类放进 {@code jellyfish-tui}</b>：{@code RunMode}、{@code StartupOptions} 与
 * {@code ExitCodes} 都定义在 {@code jellyfish-cli}，把实现放进 {@code jellyfish-tui} 会形成
 * {@code cli → tui → cli} 的循环依赖。
 * <p>
 * <b>与 CLI 模式共享的规则（原样复用，不另起一套）</b>：
 * <ol>
 *     <li>智能入口只有 {@code AgentHarness.chat}，命令入口只有 {@code CommandManager}；</li>
 *     <li>命令与对话的分流判据只有 {@code CommandManager.isCommand}；</li>
 *     <li>每轮<b>现读</b>当前会话（{@code SessionManager.current()}），不缓存 sessionId；</li>
 *     <li>{@code /exit} 归外壳，不注册为命令。</li>
 * </ol>
 * <p>
 * <b>启动前必须切日志目标</b>：TUI 会进备用屏，任何写向 stderr 的日志都会撕坏画面。
 * 切换发生在 {@code JellyfishApplication} 里（参数解析之后、DI 装配之前），
 * 因为必须在第一个 Logger 被创建之前设好 {@code log4j.configurationFile}。
 *
 * @author zcd
 */
public final class TuiRunMode implements RunMode {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(TuiRunMode.class);

    /** 智能入口：ReAct 回合的唯一门面。 */
    private final AgentHarness harness;

    /** 命令域服务：解析与分发命令。 */
    private final CommandManager commands;

    /** 会话域服务：每轮现读当前会话。 */
    private final SessionManager sessions;

    /** 模型门面：状态栏展示上下文长度用。 */
    private final ModelManager models;

    /** 输出面板：只在进入备用屏之前用于报告启动期错误。 */
    private final ConsoleIO console;

    /**
     * 构造 TUI 模式。
     *
     * @param harness  智能入口，不可为 {@code null}
     * @param commands 命令域服务，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param models   模型门面，不可为 {@code null}
     * @param console  输出面板，不可为 {@code null}
     */
    public TuiRunMode(AgentHarness harness, CommandManager commands, SessionManager sessions,
                      ModelManager models, ConsoleIO console) {
        this.harness = Objects.requireNonNull(harness, "harness must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.console = Objects.requireNonNull(console, "console must not be null");
    }

    @Override
    public boolean isImplemented() {
        return true;
    }

    /**
     * 检查是否具备可交互终端。
     * <p>
     * <b>为什么必须提前拦</b>：终端不满足时 TamboUI <b>不会报错，而是永久挂住</b>——
     * 退化到 dumb 终端后照常进事件循环，等一个永远不会来的事件，用户只看到「黑屏 + 不退出」。
     * 详细实测见 {@link TuiTerminal} 类注释。
     *
     * @param options 启动参数（本模式不使用）
     * @return 没有可交互终端时返回原因；否则返回 {@link Optional#empty()}
     */
    @Override
    public Optional<String> checkEnvironment(StartupOptions options) {
        return TuiTerminal.unsupportedReason();
    }

    @Override
    public int run(StartupOptions options) {
        try {
            new TuiApp(harness, commands, sessions, models).run();
            return ExitCodes.OK;
        } catch (JellyfishException e) {
            // 回合未收敛仍然只算正常结束：它是「答完了但没收敛」，不是执行失败。
            // 与 CLI 的 TRUNCATED 退出码不同，那是给脚本用的机器契约；TUI 是人看的界面，
            // 未收敛已经以消息区提示的形式呈现给用户了。
            LOG.error("TUI 运行失败：{}", e.getMessage(), e);
            console.writeErrLine("运行失败：" + e.getMessage());
            return ExitCodes.RUNTIME_ERROR;
        } catch (Exception e) {
            // ToolkitApp.run() 声明抛 Exception：终端初始化失败（无 TTY / 不支持的控制序列）走这里
            LOG.error("TUI 启动或运行失败：{}", e.getMessage(), e);
            console.writeErrLine("TUI 启动失败：" + e.getMessage()
                    + "（需要真实终端；请用 -cli 做单次调用）");
            return ExitCodes.RUNTIME_ERROR;
        }
    }
}
