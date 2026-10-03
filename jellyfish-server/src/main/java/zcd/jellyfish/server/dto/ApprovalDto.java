package zcd.jellyfish.server.dto;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.permission.ApprovalChannel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待审批项：SSE 的 {@code approval_required} 事件与 {@code GET /approvals} 的载荷。
 * <p>
 * 字段与 {@link ApprovalChannel.Pending} 一一对应，投影一次的理由与 {@link CommandInfoDto} 相同：
 * HTTP 合同不跟内核类型走。{@code requestId} 是裁决时回填的东西，前端必须原样带回来。
 * <p>
 * <b>关于「同一时刻只有一条」</b>：{@code ApprovalChannel} 是全局单槽位，排队中的请求对外不可见，
 * 因此本接口在任一时刻最多暴露一条待审批项。多会话并发时，一条未决会挡住其它会话的审批——
 * 这是既有内核语义，不是在接口层可以做掉的。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ApprovalDto {

    /** 请求标识。 */
    private final String requestId;

    /** 会话标识，可为 {@code null}。 */
    private final String sessionId;

    /** agentId，可为 {@code null}。 */
    private final String agentId;

    /** 待审批的工具名。 */
    private final String toolName;

    /** 工具参数，保证非 {@code null}。 */
    private final Map<String, Object> arguments;

    /** 策略给出的审批理由，可为 {@code null}。 */
    private final String reason;

    /** 请求发生时刻（epoch millis）。 */
    private final long timestamp;

    /**
     * 构造待审批项。
     *
     * @param requestId 请求标识
     * @param sessionId 会话标识，可为 {@code null}
     * @param agentId   agentId，可为 {@code null}
     * @param toolName  工具名
     * @param arguments 工具参数，可为 {@code null}
     * @param reason    审批理由，可为 {@code null}
     * @param timestamp 请求发生时刻
     */
    public ApprovalDto(String requestId, String sessionId, String agentId, String toolName,
                       Map<String, Object> arguments, String reason, long timestamp) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
        this.reason = reason;
        this.timestamp = timestamp;
    }

    /**
     * 把内核审批请求投影成 DTO。
     *
     * @param pending 内核审批请求，不可为 {@code null}
     * @return 待审批项 DTO
     */
    public static ApprovalDto of(ApprovalChannel.Pending pending) {
        return new ApprovalDto(pending.getId(), pending.getSessionId(), pending.getAgentId(),
                pending.getToolName(), pending.getArguments(), pending.getReason(),
                pending.getTimestamp());
    }

    /**
     * 获取请求标识。
     *
     * @return 请求标识
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 获取会话标识。
     *
     * @return 会话标识，可能为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取 agentId。
     *
     * @return agentId，可能为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具参数。
     *
     * @return 只读参数映射，保证非 {@code null}
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * 获取审批理由。
     *
     * @return 理由，可能为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 获取请求发生时刻。
     *
     * @return 时刻（epoch millis）
     */
    public long getTimestamp() {
        return timestamp;
    }
}
