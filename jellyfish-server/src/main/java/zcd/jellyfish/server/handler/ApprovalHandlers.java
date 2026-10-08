package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.ApprovalBridge;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.dto.ApprovalDecisionRequest;
import zcd.jellyfish.server.dto.ApprovalDto;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.JsonBody;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.util.Optional;

/**
 * 审批端点的处理器：{@code GET /sessions/{id}/approvals}、{@code POST /sessions/{id}/approvals/{requestId}}。
 * <p>
 * <b>这是一条补充路径</b>：主路径是 SSE 内嵌的 {@code approval_required} / {@code approval_resolved}
 * （见 {@link ChatHandler}），它服务「连在流上的那个客户端」。本处理器服务「晚到的客户端」（刷新后重新
 * 挂上、或非 SSE 的轮询式前端）——它拿到的是同一个头槽位。
 * <p>
 * <b>两个端点都以会话寻址，且裁决要同时满足两个条件</b>：{@code requestId} 属于这个会话、
 * 且它是这个会话的头槽位。内核的 {@code ApprovalChannel.resolve(id, approved)} 只看后者
 * （「是不是它自己那个会话的头槽位」），不校验调用方说的会话对不对——少了这一层，
 * 路径里的会话就只是个装饰，真正生效的是请求里的 id。
 * <p>
 * <b>没有「列出全部待审批」</b>：{@code ApprovalChannel} 只暴露当前头槽位，排队中的请求不可见。
 * 如实暴露现状，比造一个「也许有、也许没有」的列表接口好。
 * <p>
 * 无状态（只持有审批桥与会话域），可安全跨线程调用。
 *
 * @author zcd
 */
public final class ApprovalHandlers {

    /** 审批桥。 */
    private final ApprovalBridge approvals;

    /** 会话域服务：审批按会话寻址，先校验会话存在。 */
    private final SessionManager sessions;

    /** 运行参数（请求体上限）。 */
    private final ServerConfig config;

    /**
     * 构造处理器。
     *
     * @param approvals 审批桥，不可为 {@code null}
     * @param sessions  会话域服务，不可为 {@code null}
     * @param config    运行参数，不可为 {@code null}
     */
    public ApprovalHandlers(ApprovalBridge approvals, SessionManager sessions, ServerConfig config) {
        this.approvals = approvals;
        this.sessions = sessions;
        this.config = config;
    }

    /**
     * 处理 {@code GET /approvals}：取跨会话最早的那一条待审批项（只读的发现入口）。
     * <p>
     * <b>它存在的理由</b>：子代理的审批落在它自己的会话上，按主会话订阅的 SSE 流看不到它。
     * 客户端用这里的 {@code sessionId} 去 {@code POST /sessions/{id}/approvals/{requestId}} 裁决。
     * 它只回答「有没有、在哪个会话」，裁决仍然按会话收口——因此这不是一条绕过归属的路径。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（本端点不使用）
     */
    public void getAny(HttpServerExchange exchange, PathParams params) {
        Optional<ApprovalDto> head = approvals.head();
        if (head.isPresent()) {
            Responses.writeJson(exchange, Responses.OK, head.get());
        } else {
            Responses.writeNoContent(exchange);
        }
    }

    /**
     * 处理 {@code GET /sessions/{id}/approvals}：取该会话当前待审批项。
     * <p>
     * 没有待审批项时回 204——「没有」是常态，用空体表达比回一个 {@code null} 更干净。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id}）
     */
    public void get(HttpServerExchange exchange, PathParams params) {
        String sessionId = SessionPath.require(sessions, params.get("id")).getSessionId();
        Optional<ApprovalDto> head = approvals.headFor(sessionId);
        if (head.isPresent()) {
            Responses.writeJson(exchange, Responses.OK, head.get());
        } else {
            Responses.writeNoContent(exchange);
        }
    }

    /**
     * 处理 {@code POST /sessions/{id}/approvals/{requestId}}：裁决该会话的一条待审批项。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id} 与 {@code requestId}）
     */
    public void decide(HttpServerExchange exchange, PathParams params) {
        String sessionId = SessionPath.require(sessions, params.get("id")).getSessionId();
        ApprovalDecisionRequest request = JsonBody.read(exchange, ApprovalDecisionRequest.class,
                config.getMaxBodyBytes());
        Boolean approved = request == null ? null : request.getApproved();
        if (approved == null) {
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "approved 不能为空（true 批准，false 拒绝）");
        }
        approvals.resolveFor(sessionId, params.get("requestId"), approved.booleanValue());
        Responses.writeNoContent(exchange);
    }
}
