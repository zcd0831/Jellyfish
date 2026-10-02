package zcd.jellyfish.server;

import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.server.dto.ApprovalDto;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.Responses;

import java.util.Optional;

/**
 * 审批通道与 HTTP 之间的桥：把 {@link ApprovalChannel} 的单槽位语义翻译成接口语义。
 * <p>
 * <b>为什么需要这一层</b>：三条路径（SSE 内嵌、{@code GET /approvals}、{@code POST /approvals/{id}}）
 * 读写的都是同一个事实，散在三处必然出现「一处按 id 比对、一处不比对」这类不一致。这里把
 * 「取头槽位」「按会话过滤」「按 id 裁决」收成三四个方法，三处调用点共用。
 * <p>
 * <b>为什么不改 {@code ApprovalChannel}</b>：它是内核的既有语义（全局单槽位 + 队列 + 只对头生效），
 * 改它要重新设计审批模型，收益是「多会话并行审批」——首轮明确不做，只如实暴露现状。
 * <p>
 * 无状态（只持有通道），可安全跨线程调用。
 *
 * @author zcd
 */
public final class ApprovalBridge {

    /** 内核审批通道。 */
    private final ApprovalChannel approvals;

    /**
     * 构造桥。
     *
     * @param approvals 内核审批通道，不可为 {@code null}
     */
    public ApprovalBridge(ApprovalChannel approvals) {
        this.approvals = approvals;
    }

    /**
     * 挂上审批者：此后的 ASK 会真的等待裁决。
     * <p>
     * 与 {@code TuiRunMode} 完全对称：不挂时 ASK 一律按拒绝处理（fail-closed）。
     */
    public void attach() {
        approvals.attach();
    }

    /**
     * 摘下审批者并排空未决请求（服务停止时调用）。
     */
    public void detach() {
        approvals.detach();
    }

    /**
     * 取当前头槽位待审批项（跨会话最早的那一条）。
     * <p>
     * 只服务「不知道自己是哪个会话」的晚到客户端（{@code GET /approvals}）；
     * SSE 流应当用 {@link #headFor(String)} 取本会话的那一条。
     *
     * @return 待审批项；没有时为空
     */
    public Optional<ApprovalDto> head() {
        return approvals.pending().map(ApprovalDto::of);
    }

    /**
     * 取属于指定会话的头槽位待审批项。
     * <p>
     * 只有属于本会话的头槽位才该发给本会话的 SSE 流。
     *
     * @param sessionId 会话标识
     * @return 待审批项；该会话没有待审批项时为空
     */
    public Optional<ApprovalDto> headFor(String sessionId) {
        return approvals.pending(sessionId).map(ApprovalDto::of);
    }

    /**
     * 裁决一条待审批项。
     *
     * @param requestId 请求标识
     * @param approved  是否批准
     * @throws ApiException 请求标识不是任何会话的头槽位（或已被裁决 / 已超时）时抛出 404
     */
    public void resolve(String requestId, boolean approved) {
        if (!approvals.resolve(requestId, approved)) {
            throw new ApiException(Responses.NOT_FOUND, "APPROVAL_NOT_FOUND",
                    "没有这条待审批项（可能已被裁决或已超时）：" + requestId);
        }
    }

    /**
     * 拒绝一条仍然待审批的请求：供流断开时把卡在审批等待的 react 线程放行。
     * <p>
     * 不做「找不到就报错」：断开路径上连客户端都没了，报错也无处可去；而这条请求可能刚好
     * 被别人裁决完，那正是最正常的情形。
     *
     * @param requestId 请求标识，可为 {@code null}
     */
    public void rejectIfPending(String requestId) {
        if (requestId == null) {
            return;
        }
        approvals.resolve(requestId, false);
    }
}
