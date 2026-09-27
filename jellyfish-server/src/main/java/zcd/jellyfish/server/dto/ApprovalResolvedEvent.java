package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code approval_resolved} 的载荷：待审批项不再是当前槽位。
 * <p>
 * <b>为什么不带「批准 / 拒绝」</b>：{@code ApprovalChannel} 只暴露「当前有没有待审批项」，
 * 裁决结果、超时与否都不回传。因此本事件只表达「那条待审批项已经不在槽位上了」——
 * 这恰恰是流需要知道的事实（浮层该收起来）。真正的结果会随后以 {@code tool_done} 的形式到达：
 * 被拒的工具会带着拒绝理由失败，被批准的工具会正常返回。
 * <p>
 * 若前端需要展示「谁批的」，那属于另一轮的内核改造（暴露裁决结果），不在首轮范围。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ApprovalResolvedEvent {

    /** 请求标识。 */
    private final String requestId;

    /**
     * 构造事件。
     *
     * @param requestId 请求标识
     */
    public ApprovalResolvedEvent(String requestId) {
        this.requestId = requestId;
    }

    /**
     * 获取请求标识。
     *
     * @return 请求标识
     */
    public String getRequestId() {
        return requestId;
    }
}
