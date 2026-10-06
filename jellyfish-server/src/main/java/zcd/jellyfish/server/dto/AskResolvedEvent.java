package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code ask_resolved} 的载荷：待答提问不再是当前槽位。
 * <p>
 * <b>为什么不带答案</b>：与 {@link ApprovalResolvedEvent} 同口径——{@code AskChannel} 只暴露
 * 「当前有没有待答提问」，答复内容不回传。因此本事件只表达「那条提问已经不在槽位上了」
 * （浮层该收起来）。模型的下一步会随后以 {@code tool_done} 的形式到达：
 * 工具的结果文本里写着它收到了什么答案、以及它接下来打算怎么办。
 * <p>
 * <b>为什么答案不回传给客户端</b>：回传也没有用处——客户端本来就是给出答案的那一方；
 * 而在两条路径同时作答（SSE 内嵌与 {@code POST /asks/{id}}）时，「谁的那次生效」只有内核说得准。
 * 需要看结果就读流里的 {@code tool_done}，那是权威结果。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AskResolvedEvent {

    /** 请求标识。 */
    private final String requestId;

    /**
     * 构造事件。
     *
     * @param requestId 请求标识
     */
    public AskResolvedEvent(String requestId) {
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
