package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.core.conversation.SubmissionPolicy;
import zcd.jellyfish.core.conversation.TurnInProgressException;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.core.runtime.RunEventBus;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.ApprovalBridge;
import zcd.jellyfish.server.AskBridge;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.SseContributionListener;
import zcd.jellyfish.server.SseEvent;
import zcd.jellyfish.server.SseRunListener;
import zcd.jellyfish.server.SseTurnListener;
import zcd.jellyfish.server.dto.ApprovalDto;
import zcd.jellyfish.server.dto.ApprovalResolvedEvent;
import zcd.jellyfish.server.dto.AskDto;
import zcd.jellyfish.server.dto.AskResolvedEvent;
import zcd.jellyfish.server.dto.ChatRequest;
import zcd.jellyfish.server.dto.InputHandledEvent;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.JsonBody;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;
import zcd.jellyfish.server.http.SseWriter;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * {@code POST /sessions/{id}/chat} 的处理器：把一次 ReAct 回合以 SSE 流式返回。
 * <p>
 * <b>单写者模型</b>：socket 的写全部发生在本处理器所在的工作线程上（循环里 {@code poll → write}），
 * 可靠 lane 的订阅者只往 {@link SseTurnListener} 的队列里投事件。这样输出流只有一个写者、
 * 不需要锁；而客户端断开时 {@code write} 直接抛 {@link IOException}，天然就是取消信号。
 * <p>
 * <b>先订阅再提交</b>：回合一启动（{@code submit} 内部）就会产出事件，晚订阅会丢掉开头的
 * {@code turn_start} 与第一批增量。因此订阅在 {@code submit} 之前建立，响应结束时关闭。
 * <p>
 * <b>占位早于起回合</b>：并发回合的互斥由内核的 {@code TurnRegistry} 在 {@code submit} 内部保证
 * （{@code submit} 先占槽位再起回合），因此本类不再自己维护槽位表——它只把
 * {@link TurnInProgressException} 翻译成 409。
 * <p>
 * <b>分流不在本类</b>：输入改写、命令与指令都在 {@link ConversationService#submit} 里按内核不变量
 * 的顺序完成；本类声明 {@link SubmissionPolicy#serverChat()}（只有对话：不执行命令、不解析指令），
 * 因此 {@code /help} 与 {@code !ls} 与改造前一样是发给模型的普通文本。
 * <p>
 * <b>并发上限</b>：每个在途回合会占用一个 Undertow 工作线程直到结束（可达数分钟），
 * 因此用 {@link ServerConfig#getMaxStreams()} 封顶，超限直接 503 而不是排队——
 * 目的是保住 {@code /health}、{@code /commands} 这些短请求仍然有人应答。
 * <p>
 * <b>审批</b>：空闲 tick 时检查审批头槽位，把属于本会话的那一条以 {@code approval_required} 推给客户端；
 * 槽位消失时推一次 {@code approval_resolved}。断连时主动拒绝仍待审的那条，否则 react 线程会一直
 * 阻塞到审批超时（缺省 120 秒）；把 {@code permission.approvalTimeoutSeconds} 配成 {@code 0}
 * （永不超时）时，这条主动拒绝就是它唯一的出口。
 * <p>
 * 无状态（只持有协作者与一个信号量），可安全跨线程调用。
 *
 * @author zcd
 */
public final class ChatHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatHandler.class);

    /** 会话提交服务：分流与起回合的唯一入口。 */
    private final ConversationService conversations;

    /** 可靠 lane：回合事件的订阅入口。 */
    private final ShellStreams streams;

    /** 在途回合表：断连时取消本会话的回合。 */
    private final TurnRegistry turns;

    /** run 事件总线：订阅本会话派生的子代理 run。 */
    private final RunEventBus runEvents;

    /** 会话域服务，仅用于「会话不存在」的 404。 */
    private final SessionManager sessions;

    /** 运行参数。 */
    private final ServerConfig config;

    /** 审批桥。 */
    private final ApprovalBridge approvals;

    /** 提问桥：本流把本会话的待答提问以 {@code ask_required} / {@code ask_resolved} 推给客户端。 */
    private final AskBridge asks;

    /** 并发流许可。 */
    private final Semaphore streamPermit;

    /**
     * SSE 写循环的专用线程池。
     * <p>
     * <b>为什么不让它跑在工作线程上</b>：写 socket 会阻塞，而阻塞写没有可用的超时。客户端连上不读时，
     * 一条流就能把工作线程占到天荒地老——而工作线程还要服务 {@code /health} 这类短请求。
     * 池容量由 {@code maxStreams} 决定（见 {@link JellyfishServer}），配合下面的许可，
     * 「拿到许可一定拿得到线程」这一点成立。
     */
    private final ExecutorService streamExecutor;

    /**
     * 构造处理器。
     *
     * @param conversations 会话提交服务，不可为 {@code null}
     * @param streams  可靠 lane，不可为 {@code null}
     * @param turns    在途回合表（内核拥有），不可为 {@code null}
     * @param runEvents run 事件总线，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param config   运行参数，不可为 {@code null}
     * @param approvals 审批桥，不可为 {@code null}
     * @param asks     提问桥，不可为 {@code null}
     * @param streamExecutor SSE 写循环的线程池，不可为 {@code null}
     */
    public ChatHandler(ConversationService conversations, ShellStreams streams, TurnRegistry turns,
                       RunEventBus runEvents, SessionManager sessions, ServerConfig config,
                       ApprovalBridge approvals, AskBridge asks, ExecutorService streamExecutor) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.streams = Objects.requireNonNull(streams, "streams must not be null");
        this.turns = Objects.requireNonNull(turns, "turns must not be null");
        this.runEvents = Objects.requireNonNull(runEvents, "runEvents must not be null");
        this.sessions = sessions;
        this.config = config;
        this.approvals = approvals;
        this.asks = asks;
        this.streamExecutor = Objects.requireNonNull(streamExecutor, "streamExecutor must not be null");
        this.streamPermit = new Semaphore(config.getMaxStreams());
    }

    /**
     * 处理一次流式对话。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id}）
     */
    public void handle(HttpServerExchange exchange, PathParams params) {
        String sessionId = params.get("id");
        String message = readMessage(exchange);
        requireSession(sessionId);
        // 并发流许可先于 submit：submit 会起回合，而回合任务一提交就会 append 用户消息，
        // 资源不足时事后拒绝已经污染了历史
        if (!streamPermit.tryAcquire()) {
            throw new ApiException(Responses.SERVICE_UNAVAILABLE, "TOO_MANY_STREAMS",
                    "并发流已达上限 " + config.getMaxStreams() + "，请稍后再试");
        }
        // 先订阅再提交：turnId 由内核生成并随事件一起到达，因此本类不再自己造标识
        SseTurnListener listener = new SseTurnListener(sessionId);
        Subscription subscription = streams.subscribe(sessionId, listener);
        // 尽力 lane 没有时序要求：它的信箱会替插件把贡献存住，直到本流的写循环来取
        SseContributionListener contributions = new SseContributionListener(sessionId);
        Subscription contributionSubscription = streams.subscribeShell(contributions);
        // run 事件来自运行时总线，不是 lane：一个回合可以派生多个子代理，
        // 客户端该看到它们「在跑」，而不是只在工具结果里看到终点。
        // 过滤用「归属会话」而不是「直接父」：委派可以嵌套，孙代理的直接父是另一个子代理的临时会话，
        // 按直接父过滤会让它们一条都不显示
        SseRunListener runs = new SseRunListener(sessionId, sessions::ownerSessionId);
        Subscription runSubscription = runEvents.subscribe(runs);
        Submission submission;
        try {
            submission = conversations.submit(sessionId, message, InputTransformRequest.Source.SERVER,
                    SubmissionPolicy.serverChat());
        } catch (TurnInProgressException e) {
            // 同一会话已有在途回合：这是并发冲突，不是服务故障，也不是客户端写错了请求
            contributionSubscription.close();
            subscription.close();
            runSubscription.close();
            streamPermit.release();
            throw new ApiException(Responses.CONFLICT, "TURN_IN_PROGRESS", e.getMessage());
        } catch (RuntimeException e) {
            contributionSubscription.close();
            subscription.close();
            runSubscription.close();
            streamPermit.release();
            throw e;
        }
        if (submission.getKind() == Submission.Kind.HANDLED_INPUT) {
            // 输入被插件接过去了：没有回合，也没 append 用户消息（submit 保证），不占并发流
            contributionSubscription.close();
            subscription.close();
            runSubscription.close();
            streamPermit.release();
            writeHandled(exchange, sessionId, noticeOf(submission.getNotice()));
            return;
        }
        if (submission.getKind() != Submission.Kind.STARTED_TURN) {
            // 两个都不可能到达：BLANK_INPUT 已被 readMessage 拦下，NO_SESSION 已被 requireSession 拦下
            contributionSubscription.close();
            subscription.close();
            runSubscription.close();
            streamPermit.release();
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "/chat 只接受对话消息，命令请用 POST /sessions/{id}/commands");
        }
        Emitted emitted = new Emitted();
        // 从这一刻起是「长时间等着写」的那一段（回合可能跑几分钟，客户端还随时可能不读）。
        // 把它交给专用线程池：留在工作线程上，几条卡住的流就能把 /health 这类短请求排到队尾
        try {
            exchange.dispatch(streamExecutor, dispatched -> runStream(dispatched, sessionId, listener,
                    contributions, runs, emitted, subscription, contributionSubscription, runSubscription));
        } catch (RuntimeException e) {
            // 池已关停（服务正在停止）：把许可与订阅还回去，并如实报「稍后再试」，
            // 而不是让调用方以为回合开始了
            contributionSubscription.close();
            subscription.close();
            runSubscription.close();
            streamPermit.release();
            throw new ApiException(Responses.SERVICE_UNAVAILABLE, "TOO_MANY_STREAMS",
                    "服务正在停止，无法开始新的流式回合");
        }
    }

    /**
     * 在专用线程上消费事件流直到终态，并归还本流占用的全部资源。
     *
     * @param exchange               HTTP 交换对象
     * @param sessionId              会话标识
     * @param listener               可靠 lane 的订阅者
     * @param contributions          尽力 lane 的订阅者
     * @param runs                   run 事件总线订阅者
     * @param emitted                已推送出去的「待人工响应」请求 id
     * @param subscription           可靠 lane 订阅句柄
     * @param contributionSubscription 尽力 lane 订阅句柄
     * @param runSubscription        run 总线订阅句柄
     */
    private void runStream(HttpServerExchange exchange, String sessionId, SseTurnListener listener,
                           SseContributionListener contributions, SseRunListener runs, Emitted emitted,
                           Subscription subscription, Subscription contributionSubscription,
                           Subscription runSubscription) {
        try {
            SseWriter writer = SseWriter.prepare(exchange);
            streamUntilTerminal(writer, listener, contributions, runs, sessionId, emitted);
        } catch (IOException e) {
            // 客户端断开：这是最正常的取消来源，不记为错误
            LOG.info("SSE 客户端断开，取消回合: sessionId={}", sessionId);
            cancelQuietly(sessionId);
            approvals.rejectIfPending(emitted.approvalId);
            asks.cancelIfPending(emitted.askId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelQuietly(sessionId);
        } finally {
            contributionSubscription.close();
            subscription.close();
            runSubscription.close();
            streamPermit.release();
            exchange.endExchange();
        }
    }

    /**
     * 以 SSE 写出一条终态事件：输入被插件接过去了，没有回合可言。
     * <p>
     * 写完整流就结束，与 {@code done} / {@code cancelled} / {@code turn_blocked} 同形——
     * 客户端因此不必为本事件单写一套读取逻辑。
     *
     * @param exchange  HTTP 交换对象
     * @param sessionId 会话标识
     * @param notice    贴给用户的说明
     */
    private static void writeHandled(HttpServerExchange exchange, String sessionId, String notice) {
        try {
            SseWriter writer = SseWriter.prepare(exchange);
            writer.event("input_handled", new InputHandledEvent(sessionId, notice));
        } catch (IOException e) {
            // 客户端在拿到说明之前就断开：这是最正常的取消来源，不记为错误
            LOG.info("SSE 客户端断开，input_handled 未送达: sessionId={}", sessionId);
        } finally {
            exchange.endExchange();
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
     * 消费事件直到终态。
     *
     * @param writer             SSE 写出器
     * @param listener           可靠 lane 的订阅者（它自带队列）
     * @param contributions      尽力 lane 的订阅者（插件贡献，自带队列）
     * @param runs               run 事件总线订阅者（自带队列）
     * @param sessionId          会话标识
     * @param emitted            已推送出去的「待人工响应」请求 id，循环中原地更新
     * @throws IOException          客户端断开或写出失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private void streamUntilTerminal(SseWriter writer, SseTurnListener listener,
                                     SseContributionListener contributions, SseRunListener runs,
                                     String sessionId, Emitted emitted)
            throws IOException, InterruptedException {
        int idleSeconds = 0;
        while (true) {
            // 一秒一片地等，而不是一次等满 keepalive 间隔：回合事件一到就走（与改造前一致），
            // 而插件贡献的延后最多一秒。直接等满的话，一条通知可能要十几秒才露到屏幕上
            SseEvent event = listener.poll(1);
            if (event != null) {
                writer.event(event.getName(), event.getPayload());
                if (event.isTerminal()) {
                    return;
                }
            } else if (++idleSeconds >= config.getKeepaliveSeconds()) {
                // keepalive 的语义没变：连续空闲满一个间隔就发一帧注释，把中间设备与客户端的超时推开
                writer.comment("keepalive");
                idleSeconds = 0;
            }
            syncApproval(writer, sessionId, emitted);
            syncAsk(writer, sessionId, emitted);
            flushContributions(writer, contributions);
            flushRuns(writer, runs);
        }
    }

    /**
     * 交付本流收到的插件贡献。
     * <p>
     * <b>取（{@code drainShell}）与写分开</b>：取只会把信箱里的条目同步扇出给本进程内全部
     * 尽力 lane 订阅者（各自按会话过滤后入自己的队列），而写只发生在本线程上——
     * socket 单写者这条纪律因此不因为多了一条 lane 而改变。
     * <p>
     * <b>为什么由本线程来取</b>：交付必须发生在写线程上，否则「客户端慢」的代价会转嫁到
     * 插件的线程上。这是尽力 lane 的全部意义（可丢、不阻塞）。
     *
     * @param writer        SSE 写出器
     * @param contributions 本流的贡献订阅者
     * @throws IOException 写出失败时抛出
     */
    private void flushContributions(SseWriter writer, SseContributionListener contributions) throws IOException {
        streams.drainShell();
        SseEvent event;
        while ((event = contributions.pollNow()) != null) {
            writer.event(event.getName(), event.getPayload());
        }
    }

    /**
     * 交付本流收到的 run 事件。
     * <p>
     * 与 {@link #flushContributions} 一样「取与写分开」，但取的动作不同：run 事件由
     * {@code RunEventBus} 同步扇出，订阅者自己在 {@code accept} 里入队，因此这里只需把队列排空。
     *
     * @param writer SSE 写出器
     * @param runs   本流的 run 事件订阅者
     * @throws IOException 写出失败时抛出
     */
    private void flushRuns(SseWriter writer, SseRunListener runs) throws IOException {
        SseEvent event;
        while ((event = runs.pollNow()) != null) {
            writer.event(event.getName(), event.getPayload());
        }
    }

    /**
     * 同步审批头槽位到流：新出现则推 {@code approval_required}，消失则推 {@code approval_resolved}。
     *
     * @param writer    SSE 写出器
     * @param sessionId 会话标识
     * @param emitted   已推送的请求 id，原地更新
     * @throws IOException 写出失败时抛出
     */
    private void syncApproval(SseWriter writer, String sessionId, Emitted emitted) throws IOException {
        Optional<ApprovalDto> head = approvals.headFor(sessionId);
        if (head.isPresent()) {
            ApprovalDto dto = head.get();
            if (!dto.getRequestId().equals(emitted.approvalId)) {
                writer.event("approval_required", dto);
                emitted.approvalId = dto.getRequestId();
            }
            return;
        }
        if (emitted.approvalId != null) {
            writer.event("approval_resolved", new ApprovalResolvedEvent(emitted.approvalId));
            emitted.approvalId = null;
        }
    }

    /**
     * 同步提问头槽位到流：新出现则推 {@code ask_required}，消失则推 {@code ask_resolved}。
     * <p>
     * 与 {@link #syncApproval} 同形，但<b>顺序有讲究</b>：审批先于提问。审批是 fail-closed 的，
     * 先让它落地能让那个回合立刻继续或收敛；而提问只是一次确认，晚一帧没有代价。
     *
     * @param writer    SSE 写出器
     * @param sessionId 会话标识
     * @param emitted   已推送的请求 id，原地更新
     * @throws IOException 写出失败时抛出
     */
    private void syncAsk(SseWriter writer, String sessionId, Emitted emitted) throws IOException {
        Optional<AskDto> head = asks.headFor(sessionId);
        if (head.isPresent()) {
            AskDto dto = head.get();
            if (!dto.getRequestId().equals(emitted.askId)) {
                writer.event("ask_required", dto);
                emitted.askId = dto.getRequestId();
            }
            return;
        }
        if (emitted.askId != null) {
            writer.event("ask_resolved", new AskResolvedEvent(emitted.askId));
            emitted.askId = null;
        }
    }

    /**
     * 读取并校验请求体里的用户输入。
     *
     * @param exchange HTTP 交换对象
     * @return 非空白的输入
     * @throws ApiException 输入缺失或全为空白时抛出
     */
    private String readMessage(HttpServerExchange exchange) {
        ChatRequest request = JsonBody.read(exchange, ChatRequest.class, config.getMaxBodyBytes());
        String message = request == null ? null : request.getMessage();
        if (message == null || message.trim().isEmpty()) {
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "message 不能为空");
        }
        return message;
    }

    /**
     * 校验会话存在，不存在即 404。
     *
     * @param sessionId 会话标识
     * @throws ApiException 会话不存在时抛出
     */
    private void requireSession(String sessionId) {
        SessionPath.require(sessions, sessionId);
    }

    /**
     * 尽力取消本会话的在途回合，忽略取消本身的异常。
     *
     * @param sessionId 会话标识
     */
    private void cancelQuietly(String sessionId) {
        try {
            turns.cancel(sessionId);
        } catch (RuntimeException e) {
            LOG.warn("取消回合失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 本次流已推送出去的「待人工响应」请求 id。
     * <p>
     * <b>为什么两者要一起带着走</b>：审批与提问各有自己的头槽位，各自可能在不同时刻出现与消失，
     * 而客户端断开时两者都要收掉——只收其中一个，另一个的 {@code react} 线程要一直阻塞到超时。
     * 用一个可变对象承载，是因为它们要在写循环的每一轮里被同步更新，而循环的返回值只能带一样东西。
     */
    private static final class Emitted {

        /** 已推送的待审批请求 id，可为 {@code null}。 */
        private String approvalId;

        /** 已推送的待答提问请求 id，可为 {@code null}。 */
        private String askId;
    }
}
