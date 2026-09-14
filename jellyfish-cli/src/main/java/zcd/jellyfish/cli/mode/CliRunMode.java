package zcd.jellyfish.cli.mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.cli.ExitCodes;
import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.CliReActListener;
import zcd.jellyfish.cli.console.ConsoleIO;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.Objects;

/**
 * CLI 单次模式：进一个输入，出一次结果，进程退出。
 * <p>
 * <b>与交互式外壳的分工</b>：本模式没有提示符、没有主循环、没有 {@code /exit}——交互是 TUI 的职责。
 * 这里只保留「一次执行」这件事，好处是它能被脚本放心使用：
 * <pre>
 * jellyfish -cli -p "总结这个仓库" &gt; answer.txt 2&gt; diag.txt
 * echo "/help" | jellyfish -cli
 * </pre>
 * <p>
 * <b>命令与 LLM 的分流判据只有一个</b>：{@link CommandManager#isCommand(String)}。它只做语法判定、
 * 不查注册表——「有没有这条命令」由执行结果回答（{@code UNKNOWN}），因此插件在启动后才注册的命令
 * 也能被同一路径命中，不存在第二份清单。
 * <p>
 * <b>退出码按失败类别区分</b>：命令报错与回合失败都是 4，回合未收敛是 6，未知命令仍是 0
 * （那是用户输入错了命令名，不是程序执行失败）。这样脚本能区分「没这条命令」与「跑挂了」。
 *
 * @author zcd
 */
public final class CliRunMode implements RunMode {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CliRunMode.class);

    /** 智能入口：ReAct 回合的唯一门面。 */
    private final AgentHarness harness;

    /** 命令域服务：解析与分发命令。 */
    private final CommandManager commands;

    /** 会话域服务：每轮现读当前会话。 */
    private final SessionManager sessions;

    /** 输出面板。 */
    private final ConsoleIO console;

    /**
     * 构造 CLI 单次模式。
     *
     * @param harness  智能入口，不可为 {@code null}
     * @param commands 命令域服务，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param console  输出面板，不可为 {@code null}
     */
    public CliRunMode(AgentHarness harness, CommandManager commands, SessionManager sessions, ConsoleIO console) {
        this.harness = Objects.requireNonNull(harness, "harness must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.console = Objects.requireNonNull(console, "console must not be null");
    }

    @Override
    public boolean isImplemented() {
        return true;
    }

    @Override
    public int run(StartupOptions options) {
        String input = options.getPrompt() == null ? console.readAll() : options.getPrompt();
        if (isBlank(input)) {
            console.writeErrLine("没有输入：用 -p 指定，或从 stdin 传入（交互请用 -tui）。");
            return ExitCodes.USAGE_ERROR;
        }
        String sessionId = currentSessionId();
        if (commands.isCommand(input)) {
            return executeCommand(input, sessionId);
        }
        return executeTurn(input, sessionId, options);
    }

    /**
     * 取当前会话标识。
     * <p>
     * <b>每次现读、不缓存</b>：命令（{@code /new} {@code /resume}）会改写当前会话，
     * 缓存下来的标识会让「切换后仍往旧会话发消息」这种错悄悄发生。
     *
     * @return 当前会话标识
     * @throws JellyfishException 没有当前会话时抛出（说明启动期接线被绕过，属程序缺陷）
     */
    private String currentSessionId() {
        Session current = sessions.current();
        if (current == null) {
            throw new JellyfishException("当前没有会话：启动期未建立会话（外壳接线错误）");
        }
        return current.getSessionId();
    }

    /**
     * 执行一条命令。
     *
     * @param input     命令原文
     * @param sessionId 当前会话标识
     * @return 退出码
     */
    private int executeCommand(String input, String sessionId) {
        CommandResult result = commands.execute(input, sessionId);
        String output = result.getOutput();
        if (result.getKind() == CommandResult.Kind.ERROR) {
            console.writeErrLine(output == null || output.isEmpty() ? "命令执行失败。" : output);
            return ExitCodes.RUNTIME_ERROR;
        }
        writeCommandOutput(output);
        return ExitCodes.OK;
    }

    /**
     * 向 stdout 写出命令结果，并保证末尾有换行。
     * <p>
     * 命令返回的是「给人看的文本块」（可能是多行帮助），补一个换行让终端的下一行从行首开始；
     * 已经以换行结尾的输出不重复补。
     *
     * @param output 命令输出，可为 {@code null}
     */
    private void writeCommandOutput(String output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        console.writeOut(output.endsWith("\n") ? output : output + "\n");
    }

    /**
     * 跑一次 ReAct 回合并等待结束。
     *
     * @param input     用户输入
     * @param sessionId 当前会话标识
     * @param options   启动参数（取思考过程开关）
     * @return 退出码
     */
    private int executeTurn(String input, String sessionId, StartupOptions options) {
        CliReActListener listener = new CliReActListener(console, options.isShowThinking());
        try {
            ReActResult result = harness.chat(sessionId, input, listener).await();
            if (result.isCancelled()) {
                return ExitCodes.RUNTIME_ERROR;
            }
            if (result.isTruncated()) {
                return ExitCodes.TRUNCATED;
            }
            return ExitCodes.OK;
        } catch (JellyfishException e) {
            // 失败原因通常已由 listener.onError 打过；只有「流被中断」这类没有回调的失败才需要补一句
            if (!listener.isFailed()) {
                console.writeErrLine("回合失败：" + e.getMessage());
            }
            LOG.debug("CLI 回合失败", e);
            return ExitCodes.RUNTIME_ERROR;
        }
    }

    /**
     * 判断输入是否为空白。
     * <p>
     * 刻意不引 commons-lang3：外壳只依赖 JDK，「空白」的判定一行就够，没必要为它扩依赖面。
     *
     * @param input 输入，可为 {@code null}
     * @return 空白返回 {@code true}
     */
    private static boolean isBlank(String input) {
        return input == null || input.trim().isEmpty();
    }
}
