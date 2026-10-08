package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.AskBridge;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.dto.AskAnswerRequest;
import zcd.jellyfish.server.dto.AskDto;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.JsonBody;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.util.Optional;

/**
 * 提问端点的处理器：{@code GET /sessions/{id}/asks}、{@code POST /sessions/{id}/asks/{requestId}}。
 * <p>
 * <b>这是一条补充路径</b>：主路径是 SSE 内嵌的 {@code ask_required} / {@code ask_resolved}
 * （见 {@link ChatHandler}），它服务「连在流上的那个客户端」。本处理器服务「晚到的客户端」
 * （刷新后重新挂上、或非 SSE 的轮询式前端）——它拿到的是同一个头槽位。
 * <p>
 * <b>两个端点都以会话寻址，且作答要同时满足两个条件</b>：{@code requestId} 属于这个会话、
 * 且它是这个会话的头槽位。内核的 {@code AskChannel.resolve(id, answer)} 只看后者，
 * 不校验调用方说的会话对不对——而提问的答案会作为<b>工具结果原文进那个会话的模型上下文</b>，
 * 少了这一层就不只是越权，还是一条把任意文本注入别人对话的路径。
 * <p>
 * <b>与审批端点的差别</b>：审批的请求体是一个布尔，提问的请求体是一个答案——
 * 因此这里多一步「把两种作答形状收敛成一条 {@link AskAnswer}」。
 * 形态与 {@link ApprovalHandlers} 对称，路由也挨着它注册。
 * <p>
 * 无状态（只持有提问桥与会话域），可安全跨线程调用。
 *
 * @author zcd
 */
public final class AskHandlers {

    /** 提问桥。 */
    private final AskBridge asks;

    /** 会话域服务：提问按会话寻址，先校验会话存在。 */
    private final SessionManager sessions;

    /** 运行参数（请求体上限）。 */
    private final ServerConfig config;

    /**
     * 构造处理器。
     *
     * @param asks   提问桥，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param config 运行参数，不可为 {@code null}
     */
    public AskHandlers(AskBridge asks, SessionManager sessions, ServerConfig config) {
        this.asks = asks;
        this.sessions = sessions;
        this.config = config;
    }

    /**
     * 处理 {@code GET /asks}：取跨会话最早的那一条待答提问（只读的发现入口）。
     * <p>
     * <b>它存在的理由</b>：子代理的提问落在它自己的会话上，按主会话订阅的 SSE 流看不到它。
     * 客户端用这里的 {@code sessionId} 去 {@code POST /sessions/{id}/asks/{requestId}} 作答。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（本端点不使用）
     */
    public void getAny(HttpServerExchange exchange, PathParams params) {
        Optional<AskDto> head = asks.head();
        if (head.isPresent()) {
            Responses.writeJson(exchange, Responses.OK, head.get());
        } else {
            Responses.writeNoContent(exchange);
        }
    }

    /**
     * 处理 {@code GET /sessions/{id}/asks}：取该会话当前待答提问。
     * <p>
     * 没有待答提问时回 204——「没有」是常态，用空体表达比回一个 {@code null} 更干净。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id}）
     */
    public void get(HttpServerExchange exchange, PathParams params) {
        String sessionId = SessionPath.require(sessions, params.get("id"));
        Optional<AskDto> head = asks.headFor(sessionId);
        if (head.isPresent()) {
            Responses.writeJson(exchange, Responses.OK, head.get());
        } else {
            Responses.writeNoContent(exchange);
        }
    }

    /**
     * 处理 {@code POST /sessions/{id}/asks/{requestId}}：作答该会话的一条待答提问。
     * <p>
     * 空字符串的 {@code text} 不算作答复（那是「按了提交但什么也没写」），与
     * {@code optionId} 缺失同样处理成 400——静默当作「用户放弃了」会让一次写错的请求
     * 看起来像用户自己的决定。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id} 与 {@code requestId}）
     */
    public void answer(HttpServerExchange exchange, PathParams params) {
        String sessionId = SessionPath.require(sessions, params.get("id"));
        AskAnswerRequest request = JsonBody.read(exchange, AskAnswerRequest.class, config.getMaxBodyBytes());
        AskAnswer answer = toAnswer(request);
        asks.resolveFor(sessionId, params.get("requestId"), answer);
        Responses.writeNoContent(exchange);
    }

    /**
     * 把请求体收敛成一条答复。
     *
     * @param request 请求体，可为 {@code null}（空体）
     * @return 答复，保证非 {@code null}
     * @throws ApiException 两种作答形状都缺失时抛出 400
     */
    private static AskAnswer toAnswer(AskAnswerRequest request) {
        String optionId = request == null ? null : trimToNull(request.getOptionId());
        if (optionId != null) {
            return AskAnswer.answered(optionId);
        }
        String text = request == null ? null : trimToNull(request.getText());
        if (text != null) {
            return AskAnswer.custom(text);
        }
        throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                "optionId 与 text 至少要有一个（前者是选了某个候选项，后者是用户自己填的答案）");
    }

    /**
     * 把空白串归一成 {@code null}。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 去空白后的文本；为空时返回 {@code null}
     */
    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
