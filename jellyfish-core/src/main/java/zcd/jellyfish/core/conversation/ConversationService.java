package zcd.jellyfish.core.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.api.extension.InputTransformResult;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.core.input.InputDirectiveRun;
import zcd.jellyfish.core.input.InputTransforms;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话提交服务：把「用户敲下的一段文本」变成某个具体落点，<b>并把顺序固定在这一处</b>。
 * <p>
 * <b>它解决什么问题</b>：这段顺序此前在三个外壳各写一份（{@code TuiApp.submit()} /
 * {@code ChatHandler.handle()} / {@code CliRunMode.run()}），于是不可避免地长出了漂移——
 * {@code !} / {@code @} 只有 TUI 接、命令判定谓词两个外壳两种、建会话时机各不相同。
 * 顺序本身是<b>不变量</b>，不是每个外壳可以自行决定的实现细节，因此必须只有一份。
 * <p>
 * <b>顺序（六条，全部写在 {@link #submit} 里）</b>：
 * <ol>
 *     <li>命令判定 <b>先于</b> 输入改写：插件改不动用户显式敲下的命令；</li>
 *     <li>输入改写 <b>先于</b> 输入指令解析：指令按改写后的文本解析；</li>
 *     <li>输入改写 <b>先于</b> 建会话：被插件接过去的输入不该顺手留下一个空会话；</li>
 *     <li>输入指令 <b>先于</b> 普通对话：{@code !} 这类行首标记优先被认领；</li>
 *     <li>{@code REQUIRE_EXISTING} 且无会话时当场拒绝，<b>绝不建会话</b>；</li>
 *     <li>{@code commands=false} 时「看起来像命令」的输入按普通文本处理（不报错、不静默丢）。</li>
 * </ol>
 * <p>
 * <b>命令可以「接力」</b>：命令返回 {@link CommandResult#handoff(String)} 时，本类不把结果交给外壳，
 * 而是<b>用接力文本替换本次输入</b>后继续往下走 2)~6)。这是「命令只声明、内核负责执行」的又一例
 * （对标输入指令的 {@code InputDirectiveResult}）——插件因此获得了「替用户说一句话」的表达力，
 * 而<b>没有</b>获得起回合的能力：回合仍然起在这一个方法里，仍然由调用方这次提交拥有。
 * 接力文本与用户手敲的文本在下游逐字段一致（同样会被输入改写、同样进会话历史、同样过权限链）。
 * <p>
 * <b>它不管渲染</b>：不缓冲任何输出，不决定任何格式；回合 / 指令的实时输出仍走调用方传入的
 * {@link ReActListener}（P2 之后改为可靠 lane 的订阅）。本类的返回值只回答「发生了什么」。
 * <p>
 * <b>它拥有回合闸门的“入口”</b>：起回合前向 {@link TurnRegistry} 占位，终态时由
 * {@code TurnRegistry.releasing} 的包装器自动归还；第二个并发提交在占位处被拒
 * （{@link TurnInProgressException}，Server 映射 409）。因此「同一会话同时只能有一个回合」是内核不变量，
 * 不再是每个外壳各自实现一遍的东西。
 * <p>
 * <b>它不管取消</b>：取消入口是 {@code TurnRegistry.cancel(sessionId)}，外壳直接调它。
 * <p>
 * 无状态（只持有协作者），可安全跨线程调用。
 *
 * @author zcd
 */
@Singleton
public class ConversationService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConversationService.class);

    /** 命令域服务：判定与执行。 */
    private final CommandManager commands;

    /** 输入改写服务。 */
    private final InputTransforms inputTransforms;

    /** 输入指令服务。 */
    private final InputDirectives inputDirectives;

    /** 会话域服务：读当前态与按需建会话。 */
    private final SessionManager sessions;

    /** 每会话在途回合表：起回合的互斥与取消。 */
    private final TurnRegistry turns;

    /** 可靠 lane：回合事件的唯一出口。 */
    private final ShellStreams streams;

    /** 智能入口：起 ReAct 回合。 */
    private final AgentHarness harness;

    /**
     * 构造会话提交服务。
     *
     * @param commands       命令域服务，不可为 {@code null}
     * @param inputTransforms 输入改写服务，不可为 {@code null}
     * @param inputDirectives 输入指令服务，不可为 {@code null}
     * @param sessions       会话域服务，不可为 {@code null}
     * @param turns          在途回合表，不可为 {@code null}
     * @param streams        可靠 lane，不可为 {@code null}
     * @param harness        智能入口，不可为 {@code null}
     */
    @Inject
    public ConversationService(CommandManager commands, InputTransforms inputTransforms,
                               InputDirectives inputDirectives, SessionManager sessions, TurnRegistry turns,
                               ShellStreams streams, AgentHarness harness) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.inputTransforms = Objects.requireNonNull(inputTransforms, "inputTransforms must not be null");
        this.inputDirectives = Objects.requireNonNull(inputDirectives, "inputDirectives must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.turns = Objects.requireNonNull(turns, "turns must not be null");
        this.streams = Objects.requireNonNull(streams, "streams must not be null");
        this.harness = Objects.requireNonNull(harness, "harness must not be null");
    }

    /**
     * 处理一次用户提交。
     * <p>
     * <b>本方法可能创建会话</b>（{@code CREATE_IF_NEEDED}）、可能起回合、可能起指令，因此调用方必须
     * 在调用前做好自己的准备：TUI 需要先重置界面暂存区，而<b>所有外壳必须先建立可靠 lane 的订阅</b>
     * （{@code ShellStreams.subscribe} / {@code subscribeAll}）——回合一启动就会产出事件，
     * 晚订阅的会丢掉开头那一段。
     *
     * @param sessionId 会话标识，可为 {@code null}（首页 / 无会话）
     * @param text      用户输入原文，可为 {@code null}（等价空串，落 {@link Submission.RejectReason#BLANK_INPUT}）
     * @param source    输入来自哪种外壳，不可为 {@code null}
     * @param policy    提交策略，不可为 {@code null}
     * @return 处理结果，保证非 {@code null}
     * @throws JellyfishException 会话已存在但读取失败（如 {@code REQUIRE_EXISTING} 下会话不存在时
     *                            由 {@code InputDirectives.start} 抛出）、或起回合失败时抛出；
     *                            用户输入错误与插件缺陷不会被抛成本异常
     * @throws TurnInProgressException 同一会话已有在途回合时抛出（外壳据此映射 409 / 提示）
     */
    public Submission submit(String sessionId, String text, InputTransformRequest.Source source,
                             SubmissionPolicy policy) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        String input = text == null ? "" : text;
        if (input.trim().isEmpty()) {
            return Submission.rejected(sessionId, Submission.RejectReason.BLANK_INPUT);
        }

        boolean hasSession = sessionId != null && !sessionId.trim().isEmpty();

        // 1) 命令判定先于输入改写：插件改不动用户显式敲下的命令
        if (policy.isCommands() && commands.shouldRunAsCommand(input, hasSession)) {
            CommandResult result = commands.execute(input, sessionId);
            if (!result.hasHandoff()) {
                return Submission.command(sessionId, result);
            }
            // 命令接力：命令只声明「把这段文本当用户输入」，替换之后照旧走 2)~6) 那条路。
            // 回合因此仍然起在这一处、由这一次提交拥有——插件只是替用户说了一句话，
            // 既没拿到起回合的能力，也没绕过后面任何一道闸门（改写、指令、权限都照走）
            LOG.debug("命令接力，本次输入已替换为命令给出的文本: source={}", source);
            input = result.getHandoffText();
        }

        // 2)+3) 输入改写先于指令解析、先于建会话
        InputTransformResult transformed = inputTransforms.transform(sessionId, input, source);
        if (transformed.isHandled()) {
            return Submission.handled(sessionId, transformed.getNotice());
        }
        if (transformed.hasText()) {
            String replaced = transformed.getText();
            if (replaced != null) {
                input = replaced;
            }
        }

        if (!hasSession) {
            if (policy.getSessions() == SubmissionPolicy.SessionPolicy.REQUIRE_EXISTING) {
                // 刻意不建：Server 按 id 寻址，建一个「谁都没用过」的会话只会留下一个空文件
                return Submission.rejected(null, Submission.RejectReason.NO_SESSION);
            }
            Session created = sessions.createDefault();
            sessions.switchTo(created.getSessionId());
            sessionId = created.getSessionId();
        }

        // 4) 输入指令先于普通对话：指令也发布到可靠 lane（它有自己的实时输出）
        if (policy.isDirectives()) {
            final String directiveSession = sessionId;
            String directiveId = newTurnId();
            ReActListener publisher = streams.publisher(directiveSession, directiveId);
            // 结束通知在提交之前就交进去：指令可能很短，短到在你拿到句柄之前已经跑完，
            // 而「先提交、后注册」会漏掉那一次；可靠 lane 的契约是「每个标识的事件流恰好一条终态」，
            // 漏一条就等于向订阅者承诺了一件不会发生的事（按契约实现的订阅者会一直等下去）
            Optional<InputDirectiveRun> run = inputDirectives.submit(directiveSession, input, publisher,
                    finished -> streams.publish(finished.isCancelled()
                            ? ShellTurnEvent.cancelled(directiveSession, directiveId)
                            : ShellTurnEvent.completed(directiveSession, directiveId, null, 0, false)));
            if (run.isPresent()) {
                return Submission.directive(directiveSession, directiveId, run.get());
            }
        }

        // 5) 起回合：先占槽位（互斥在传递路径的最前），终态时由包装器自动归还。
        //    占位必须早于 harness.chat —— 回合任务一提交就会 append 用户消息，事后判冲突已污染历史。
        //    回合标识在这里生成（不能等句柄）：订阅者要在回合启动之前就能拿到它。
        String turnId = newTurnId();
        TurnRegistry.Slot slot = turns.acquire(sessionId);
        ReActListener tracked = turns.releasing(sessionId, slot, streams.publisher(sessionId, turnId));
        // STARTED 在这里发而不是等执行体回调：ReActListener 没有「开始」这个方法，
        // 而订阅者必须在第一批增量之前就能拿到回合标识。它也可能是本回合的唯一条事件
        // （起回合就失败）——因此下面 catch 里必须补一条终态，否则等终态的调用方会永久挂住
        streams.publish(ShellTurnEvent.started(sessionId, turnId));
        try {
            ReActTurn turn = harness.chat(sessionId, turnId, input, tracked);
            // 带槽位登记：回合可能在 chat 返回之前就已经收敛（槽位随之归还），
            // 那种迟到的句柄必须被挡在外面，否则它会盖掉下一个回合，让 Esc 指向一个已结束的回合
            turns.bind(slot, turn);
            LOG.debug("提交进入 ReAct 回合: sessionId={} turnId={} source={}", sessionId, turnId, source);
            return Submission.turn(sessionId, turnId);
        } catch (RuntimeException e) {
            // 回合根本没起来：先补终态（订阅者在等它），再归还槽位，否则该会话以后再也起不了回合
            streams.publish(ShellTurnEvent.error(sessionId, turnId, e));
            turns.release(sessionId, slot);
            throw e;
        }
    }

    /**
     * 生成一个回合 / 指令标识。
     * <p>
     * 用 UUID 而不是递增计数器：标识要跨进程、跨会话关联（SSE 的 {@code turnId}），
     * 而计数器在重启后会从头开始，与客户端手里的旧标识相撞。
     *
     * @return 标识，保证非空白
     */
    private static String newTurnId() {
        return java.util.UUID.randomUUID().toString();
    }
}
