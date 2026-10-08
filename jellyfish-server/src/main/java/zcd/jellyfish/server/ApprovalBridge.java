package zcd.jellyfish.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.server.dto.ApprovalDto;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.LogText;
import zcd.jellyfish.server.http.Responses;

import java.util.Optional;

/**
 * 审批通道与 HTTP 之间的桥：把 {@link ApprovalChannel} 按会话的头槽位语义翻译成接口语义。
 * <p>
 * <b>为什么需要这一层</b>：三条路径（SSE 内嵌、{@code GET /approvals}、{@code POST /approvals/{id}}）
 * 读写的都是同一个事实，散在三处必然出现「一处按 id 比对、一处不比对」这类不一致。这里把
 * 「取本会话的头槽位」「按会话过滤」「按 id 裁决」收成三四个方法，三处调用点共用。
 * <p>
 * <b>它是只读的一侧</b>：审批的判定与队列语义全在 {@code ApprovalChannel} 里，
 * 这里只做投影与错误映射，不自己维护任何审批状态。
 * <p>
 * 无状态（只持有通道），可安全跨线程调用。
 *
 * @author zcd
 */
public final class ApprovalBridge {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalBridge.class);

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
     * 取当前头槽位待审批项（跨会话最早的那一条）：<b>只读的发现入口</b>。
     * <p>
     * <b>为什么需要它</b>：子代理跑在它自己的会话上，它的审批落在那个会话的头槽位——按主会话订阅的
     * SSE 流看不到它（{@link #headFor(String)} 取的是主会话自己那一条）。没有这个跨会话入口，
     * 那条审批就只能等到超时被拒。客户端拿它的 {@code sessionId} 去
     * {@code POST /sessions/{id}/approvals/{requestId}} 裁决。
     * <p>
     * <b>它只回答「有没有、在哪个会话」，不回答「该不该批准」</b>：裁决一律按会话寻址（见
     * {@link #resolveFor(String, String, boolean)}），因此这个入口不构成「绕过归属」的路径。
     *
     * @return 待审批项；没有时为空
     */
    public Optional<ApprovalDto> head() {
        return approvals.pending().map(ApprovalDto::of);
    }

    /**
     * 取属于指定会话的头槽位待审批项。
     * <p>
     * 只有属于本会话的头槽位才该发给本会话的客户端。
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
            // 文案不带 requestId：它会随响应进客户端与日志，未清洗时能伪造日志行、改终端显示
            LOG.info("待审批项不存在或已被裁决: {}", LogText.singleLine(requestId));
            throw new ApiException(Responses.NOT_FOUND, "APPROVAL_NOT_FOUND",
                    "没有这条待审批项（可能已被裁决或已超时）");
        }
    }

    /**
     * 裁决<b>指定会话</b>的一条待审批项：先确认它属于该会话且是头槽位，再裁决。
     * <p>
     * <b>为什么必须多这一步</b>：{@code ApprovalChannel.resolve(id, approved)} 只校验「这条请求是不是
     * 它自己那个会话的头槽位」，<b>不校验调用方说的会话对不对</b>——拿「我的会话 id + 别人的 requestId」
     * 去调它，内核会照办。少了这一层，HTTP 上的两个参数就是各说各话：
     * 路径里的会话只是装饰，真正生效的是请求体里的 id。
     * <p>
     * <b>头槽位是每会话一个</b>：所以「属于本会话」＝「是本会话队列里的第一条」。
     * 排队中的那一条裁决它等于无事发生（内核的既有语义），这里如实回 404。
     *
     * @param sessionId 会话标识
     * @param requestId 请求标识
     * @param approved  是否批准
     * @throws ApiException 请求标识不属于该会话、或不是头槽位（已裁决 / 已超时 / 还在排队）时抛出 404
     */
    public void resolveFor(String sessionId, String requestId, boolean approved) {
        if (!isHeadOf(sessionId, requestId)) {
            LOG.info("待审批项不属于该会话或不是头槽位: sessionId={} requestId={}",
                    LogText.singleLine(sessionId), LogText.singleLine(requestId));
            throw new ApiException(Responses.NOT_FOUND, "APPROVAL_NOT_FOUND",
                    "这条待审批项不属于该会话（或不是头槽位，或已裁决 / 已超时）");
        }
        resolve(requestId, approved);
    }

    /**
     * 判断某条待审批项是不是指定会话的头槽位。
     *
     * @param sessionId 会话标识
     * @param requestId 请求标识，可为 {@code null}
     * @return 是头槽位返回 {@code true}
     */
    private boolean isHeadOf(String sessionId, String requestId) {
        if (requestId == null) {
            return false;
        }
        for (ApprovalChannel.Pending pending : approvals.pendingApprovals(sessionId)) {
            // 第一条就是头槽位（其后的都在排队区，裁决它们等于无事发生）
            return requestId.equals(pending.getId());
        }
        return false;
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
