package zcd.jellyfish.cli.mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.cli.ExitCodes;
import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.CliTurnListener;
import zcd.jellyfish.cli.console.ConsoleIO;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.core.conversation.SubmissionPolicy;
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
 * <b>「命令还是对话」的判定不在本类</b>：分流顺序（命令判定 → 输入改写 → 输入指令 → 起回合）
 * 是内核不变量，统一在 {@link ConversationService#submit} 一处实现；本类只声明自己的策略
 * （{@link SubmissionPolicy#cli()}：执行命令、<b>不解析输入指令</b>、必须有会话）并把
 * {@link Submission} 的判别式结果翻译成退出码。
 * <p>
 * <b>回合事件走可靠 lane</b>：本类不再自己实现 {@code ReActListener}，而是<b>先订阅再提交</b>
 * （{@code submit} 内部会起回合并立即产出事件），然后用终态事件的闩锁替代了过去的
 * {@code ReActTurn.await()}——退出码由终态事件的种类决定，不再需要回合句柄。
 * <p>
 * <b>退出码按失败类别区分</b>：命令报错与回合失败都是 4，回合未收敛是 6，未知命令仍是 0
 * （那是用户输入错了命令名，不是程序执行失败）。这样脚本能区分「没这条命令」与「跑挂了」。
 *
 * @author zcd
 */
public final class CliRunMode implements RunMode {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CliRunMode.class);

    /** 会话提交服务：分流与起回合的唯一入口。 */
    private final ConversationService conversations;

    /** 可靠 lane：回合事件的订阅入口。 */
    private final ShellStreams streams;

    /** 会话域服务：每轮现读当前会话。 */
    private final SessionManager sessions;

    /** 输出面板。 */
    private final ConsoleIO console;

    /**
     * 构造 CLI 单次模式。
     *
     * @param conversations 会话提交服务，不可为 {@code null}
     * @param streams       可靠 lane，不可为 {@code null}
     * @param sessions      会话域服务，不可为 {@code null}
     * @param console       输出面板，不可为 {@code null}
     */
    public CliRunMode(ConversationService conversations, ShellStreams streams, SessionManager sessions,
                      ConsoleIO console) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.streams = Objects.requireNonNull(streams, "streams must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.console = Objects.requireNonNull(console, "console must not be null");
    }

    @Override
    public RuntimeInfo.Shell shell() {
        // 单次调用、不交互：没有可交互界面，也没有审批者（需要审批的调用一律按拒绝处理）
        return RuntimeInfo.Shell.CLI;
    }

    @Override
    public int run(StartupOptions options) {
        String input = options.getPrompt() == null ? console.readAll() : options.getPrompt();
        if (isBlank(input)) {
            console.writeErrLine("没有输入：用 -p 指定，或从 stdin 传入（交互请用 -tui）。");
            return ExitCodes.USAGE_ERROR;
        }
        String sessionId = currentSessionId();
        CliTurnListener listener = new CliTurnListener(console, options.isShowThinking(),
                options.isShowToolArgs());
        // 必须先订阅再提交：回合一启动就会产出事件，晚订阅会丢掉开头那一段
        Subscription subscription = streams.subscribe(sessionId, listener);
        try {
            Submission submission = conversations.submit(sessionId, input,
                    InputTransformRequest.Source.CLI, SubmissionPolicy.cli());
            switch (submission.getKind()) {
                case EXECUTED_COMMAND:
                    return commandExitCode(submission.getCommandResult());
                case HANDLED_INPUT:
                    // 输入被插件接过去了：没有回答，写一条说明到 stdout（与命令结果同一条通道）
                    writeCommandOutput(noticeOf(submission.getNotice()));
                    return ExitCodes.OK;
                case STARTED_TURN:
                    return awaitTurn(listener);
                case STARTED_DIRECTIVE:
                case REJECTED:
                default:
                    // 两个都不可能到达：cli() 不解析输入指令；空输入已在上面拦下、会话由启动期保证
                    console.writeErrLine("没有可执行的输入（外壳接线错误）。");
                    return ExitCodes.RUNTIME_ERROR;
            }
        } catch (JellyfishException e) {
            // 失败原因通常已由 listener 打过；只有「流被中断」这类没有回调的失败才需要补一句
            if (!listener.isFailed()) {
                console.writeErrLine("回合失败：" + e.getMessage());
            }
            LOG.debug("CLI 提交失败", e);
            return ExitCodes.RUNTIME_ERROR;
        } finally {
            subscription.close();
        }
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
     * 把命令结果翻译成退出码。
     * <p>
     * 未知命令仍是 0：那是用户输入错了命令名，不是程序执行失败；只有命令报错才是 4。
     *
     * @param result 命令结果，保证非 {@code null}
     * @return 退出码
     */
    private int commandExitCode(CommandResult result) {
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
     * @param output 输出，可为 {@code null}
     */
    private void writeCommandOutput(String output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        console.writeOut(output.endsWith("\n") ? output : output + "\n");
    }

    /**
     * 等待一次 ReAct 回合结束并映射退出码。
     * <p>
     * 退出码只看终态事件：拦下与被取消互斥，但两者都比「未收敛」先判——
     * 一个根本没跑起来的回合不该被说成「达到最大轮次」。
     *
     * @param listener 已经交出去的订阅者（它同时是终态闩锁），不可为 {@code null}
     * @return 退出码
     */
    private int awaitTurn(CliTurnListener listener) {
        ShellTurnEvent terminal = listener.awaitTerminal();
        if (terminal == null) {
            // 等待被中断且终态尚未到达：按运行失败处理（中断位已在 listener 里恢复）
            return ExitCodes.RUNTIME_ERROR;
        }
        switch (terminal.getKind()) {
            case BLOCKED:
                return ExitCodes.TURN_BLOCKED;
            case CANCELLED:
                return ExitCodes.RUNTIME_ERROR;
            case COMPLETED:
                return terminal.isTruncated() ? ExitCodes.TRUNCATED : ExitCodes.OK;
            case ERROR:
            default:
                return ExitCodes.RUNTIME_ERROR;
        }
    }

    /**
     * 取插件给出说明的可用文本。
     *
     * @param notice 说明，可为 {@code null}
     * @return 说明文本，空时返回固定占位
     */
    private static String noticeOf(String notice) {
        return notice == null || notice.trim().isEmpty() ? "输入已被插件接过去" : notice;
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
