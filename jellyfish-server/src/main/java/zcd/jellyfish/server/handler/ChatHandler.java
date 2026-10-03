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
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.SseContributionListener;
import zcd.jellyfish.server.SseEvent;
import zcd.jellyfish.server.SseRunListener;
import zcd.jellyfish.server.SseTurnListener;
import zcd.jellyfish.server.dto.ApprovalDto;
import zcd.jellyfish.server.dto.ApprovalResolvedEvent;
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
 * 阻塞到审批超时（缺省 120 秒）。
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

    /** 并发流许可。 */
    private final Semaphore streamPermit;

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
     */
    public ChatHandler(ConversationService conversations, ShellStreams streams, TurnRegistry turns,
                       RunEventBus runEvents, SessionManager sessions, ServerConfig config,
                       ApprovalBridge approvals) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.streams = Objects.requireNonNull(streams, "streams must not be null");
        this.turns = Objects.requireNonNull(turns, "turns must not be null");
        this.runEvents = Objects.requireNonNull(runEvents, "runEvents must not be null");
        this.sessions = sessions;
        this.config = config;
        this.approvals = approvals;
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
        // 客户端该看到它们「在跑」，而不是只在工具结果里看到终点
        SseRunListener runs = new SseRunListener(sessionId);
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
        String emittedApprovalId = null;
        try {
            SseWriter writer = SseWriter.prepare(exchange);
            emittedApprovalId = streamUntilTerminal(writer, listener, contributions, runs, sessionId,
                    emittedApprovalId);
        } catch (IOException e) {
            // 客户端断开：这是最正常的取消来源，不记为错误
            LOG.info("SSE 客户端断开，取消回合: sessionId={}", sessionId);
            cancelQuietly(sessionId);
            approvals.rejectIfPending(emittedApprovalId);
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
     * @param emittedApprovalId  已推送给客户端的审批请求 id，可为 {@code null}
     * @return 循环结束时仍待审批的请求 id，可为 {@code null}
     * @throws IOException          客户端断开或写出失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private String streamUntilTerminal(SseWriter writer, SseTurnListener listener,
                                       SseContributionListener contributions, SseRunListener runs,
                                       String sessionId, String emittedApprovalId)
            throws IOException, InterruptedException {
        String pending = emittedApprovalId;
        int idleSeconds = 0;
        while (true) {
            // 一秒一片地等，而不是一次等满 keepalive 间隔：回合事件一到就走（与改造前一致），
            // 而插件贡献的延后最多一秒。直接等满的话，一条通知可能要十几秒才露到屏幕上
            SseEvent event = listener.poll(1);
            if (event != null) {
                writer.event(event.getName(), event.getPayload());
                if (event.isTerminal()) {
                    return pending;
                }
            } else if (++idleSeconds >= config.getKeepaliveSeconds()) {
                // keepalive 的语义没变：连续空闲满一个间隔就发一帧注释，把中间设备与客户端的超时推开
                writer.comment("keepalive");
                idleSeconds = 0;
            }
            pending = syncApproval(writer, sessionId, pending);
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
     * @param emittedId 已推送的请求 id，可为 {@code null}
     * @return 本次推送后仍待审批的请求 id，可为 {@code null}
     * @throws IOException 写出失败时抛出
     */
    private String syncApproval(SseWriter writer, String sessionId, String emittedId) throws IOException {
        Optional<ApprovalDto> head = approvals.headFor(sessionId);
        if (head.isPresent()) {
            ApprovalDto dto = head.get();
            if (!dto.getRequestId().equals(emittedId)) {
                writer.event("approval_required", dto);
                return dto.getRequestId();
            }
            return emittedId;
        }
        if (emittedId != null) {
            writer.event("approval_resolved", new ApprovalResolvedEvent(emittedId));
        }
        return null;
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
        try {
            sessions.require(sessionId);
        } catch (JellyfishException e) {
            throw new ApiException(Responses.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在：" + sessionId);
        }
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
}
