package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.ApprovalBridge;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.SessionTurns;
import zcd.jellyfish.server.SseEvent;
import zcd.jellyfish.server.SseReActListener;
import zcd.jellyfish.server.dto.ApprovalDto;
import zcd.jellyfish.server.dto.ApprovalResolvedEvent;
import zcd.jellyfish.server.dto.ChatRequest;
import zcd.jellyfish.server.dto.TurnStartEvent;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.JsonBody;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;
import zcd.jellyfish.server.http.SseWriter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * {@code POST /sessions/{id}/chat} 的处理器：把一次 ReAct 回合以 SSE 流式返回。
 * <p>
 * <b>单写者模型</b>：socket 的写全部发生在本处理器所在的工作线程上（循环里 {@code poll → write}），
 * {@code react} 线程只往 {@link SseReActListener} 的队列里投事件。这样输出流只有一个写者、
 * 不需要锁；而客户端断开时 {@code write} 直接抛 {@link IOException}，天然就是取消信号。
 * <p>
 * <b>占位早于起回合</b>：{@link SessionTurns#acquire} 在 {@code harness.chat} 之前调用——
 * 回合任务一提交就会 append 用户消息，若「先起回合再判断冲突」，被拒的请求已经污染了会话历史。
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

    /** 智能入口。 */
    private final AgentHarness harness;

    /** 会话域服务，仅用于「会话不存在」的 404。 */
    private final SessionManager sessions;

    /** 每会话在途回合表。 */
    private final SessionTurns turns;

    /** 运行参数。 */
    private final ServerConfig config;

    /** 审批桥。 */
    private final ApprovalBridge approvals;

    /** 并发流许可。 */
    private final Semaphore streamPermit;

    /**
     * 构造处理器。
     *
     * @param harness   智能入口，不可为 {@code null}
     * @param sessions  会话域服务，不可为 {@code null}
     * @param turns     在途回合表，不可为 {@code null}
     * @param config    运行参数，不可为 {@code null}
     * @param approvals 审批桥，不可为 {@code null}
     */
    public ChatHandler(AgentHarness harness, SessionManager sessions, SessionTurns turns,
                       ServerConfig config, ApprovalBridge approvals) {
        this.harness = harness;
        this.sessions = sessions;
        this.turns = turns;
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
        Semaphore slot = turns.acquire(sessionId);
        if (!streamPermit.tryAcquire()) {
            turns.release(sessionId, slot);
            throw new ApiException(Responses.SERVICE_UNAVAILABLE, "TOO_MANY_STREAMS",
                    "并发流已达上限 " + config.getMaxStreams() + "，请稍后再试");
        }
        ReActTurn turn = null;
        String emittedApprovalId = null;
        try {
            // turnId 由外壳生成：它是 SSE 的关联标识，必须在 chat 之前就确定，否则早期回调会带 null
            String turnId = UUID.randomUUID().toString();
            SseReActListener listener = new SseReActListener(sessionId, turnId);
            turn = harness.chat(sessionId, message, listener);
            turns.bind(sessionId, turn);
            SseWriter writer = SseWriter.prepare(exchange);
            writer.event("turn_start", new TurnStartEvent(turnId, sessionId));
            emittedApprovalId = streamUntilTerminal(writer, listener, sessionId, emittedApprovalId);
        } catch (IOException e) {
            // 客户端断开：这是最正常的取消来源，不记为错误
            LOG.info("SSE 客户端断开，取消回合: sessionId={}", sessionId);
            cancelQuietly(turn);
            approvals.rejectIfPending(emittedApprovalId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelQuietly(turn);
        } finally {
            turns.release(sessionId, slot);
            streamPermit.release();
            exchange.endExchange();
        }
    }

    /**
     * 消费事件直到终态。
     *
     * @param writer             SSE 写出器
     * @param listener           监听器
     * @param sessionId          会话标识
     * @param emittedApprovalId  已推送给客户端的审批请求 id，可为 {@code null}
     * @return 循环结束时仍待审批的请求 id，可为 {@code null}
     * @throws IOException          客户端断开或写出失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private String streamUntilTerminal(SseWriter writer, SseReActListener listener, String sessionId,
                                       String emittedApprovalId) throws IOException, InterruptedException {
        String pending = emittedApprovalId;
        while (true) {
            SseEvent event = listener.poll(config.getKeepaliveSeconds());
            if (event == null) {
                writer.comment("keepalive");
                pending = syncApproval(writer, sessionId, pending);
                continue;
            }
            writer.event(event.getName(), event.getPayload());
            if (event.isTerminal()) {
                return pending;
            }
            pending = syncApproval(writer, sessionId, pending);
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
     * 尽力取消回合，忽略取消本身的异常。
     *
     * @param turn 回合句柄，可为 {@code null}
     */
    private static void cancelQuietly(ReActTurn turn) {
        if (turn == null) {
            return;
        }
        try {
            turn.cancel();
        } catch (RuntimeException e) {
            LOG.warn("取消回合失败（忽略）: {}", e.getMessage());
        }
    }
}
