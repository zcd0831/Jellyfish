package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
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
 * 审批端点的处理器：{@code GET /approvals}、{@code POST /approvals/{requestId}}。
 * <p>
 * <b>这是一条补充路径</b>：主路径是 SSE 内嵌的 {@code approval_required} / {@code approval_resolved}
 * （见 {@link ChatHandler}），它服务「连在流上的那个客户端」。本处理器服务「晚到的客户端」（刷新后重新
 * 挂上、或非 SSE 的轮询式前端）——它拿到的是同一个头槽位。
 * <p>
 * <b>为什么没有「列出全部待审批」</b>：{@code ApprovalChannel} 只暴露当前头槽位，排队中的请求不可见。
 * 如实暴露现状，比造一个「也许有、也许没有」的列表接口好。
 * <p>
 * 无状态（只持有审批桥），可安全跨线程调用。
 *
 * @author zcd
 */
public final class ApprovalHandlers {

    /** 审批桥。 */
    private final ApprovalBridge approvals;

    /** 运行参数（请求体上限）。 */
    private final ServerConfig config;

    /**
     * 构造处理器。
     *
     * @param approvals 审批桥，不可为 {@code null}
     * @param config    运行参数，不可为 {@code null}
     */
    public ApprovalHandlers(ApprovalBridge approvals, ServerConfig config) {
        this.approvals = approvals;
        this.config = config;
    }

    /**
     * 处理 {@code GET /approvals}：取当前头槽位待审批项。
     * <p>
     * 没有待审批项时回 204——「没有」是常态，用空体表达比回一个 {@code null} 更干净。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（本端点不使用）
     */
    public void get(HttpServerExchange exchange, PathParams params) {
        Optional<ApprovalDto> head = approvals.head();
        if (head.isPresent()) {
            Responses.writeJson(exchange, Responses.OK, head.get());
        } else {
            Responses.writeNoContent(exchange);
        }
    }

    /**
     * 处理 {@code POST /approvals/{requestId}}：裁决一条待审批项。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code requestId}）
     */
    public void decide(HttpServerExchange exchange, PathParams params) {
        ApprovalDecisionRequest request = JsonBody.read(exchange, ApprovalDecisionRequest.class,
                config.getMaxBodyBytes());
        Boolean approved = request == null ? null : request.getApproved();
        if (approved == null) {
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "approved 不能为空（true 批准，false 拒绝）");
        }
        approvals.resolve(params.get("requestId"), approved);
        Responses.writeNoContent(exchange);
    }
}
