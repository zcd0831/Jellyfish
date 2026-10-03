package zcd.jellyfish.core.runtime;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;

/**
 * 一次 agent run 的登记输入：这个 run 属于谁、要跑什么、在哪个会话里跑。
 * <p>
 * <b>为什么不带 {@code parentRunId} / {@code rootRunId}</b>：父子关系是运行时从「当前执行路径的
 * 上下文」推出来的事实，不是调用方按字面填的字段。让调用方在这里传一遍，等于把同一份关系说两次，
 * 而两次说法必然会漂移（尤其是并行之后，父子树由调度器维护）。因此这两个值由
 * {@code AgentRuntime.spawn} 在登记时补齐。
 * <p>
 * <b>为什么叫「登记输入」而不是「执行请求」</b>：本类型只承载 {@link RunRegistry} 需要的事实
 * （所属会话、agent、工具调用别名），不含模型、工具清单与提示词——那三样由 {@code agentId} 指向的
 * agent 定义决定，让调用方在这里再传一遍就会与定义漂移。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AgentRunRequest {

    /** 派生这次 run 的父会话标识。 */
    private final String parentSessionId;

    /** 被委派到的 agent 标识。 */
    private final String agentId;

    /** 这个 run 自己的会话标识。 */
    private final String sessionId;

    /** 关联的工具调用标识；不是由 {@code task} 工具触发时为 {@code null}。 */
    private final String toolCallId;

    /**
     * 构造一次 run 的登记输入。
     *
     * @param parentSessionId 父会话标识，不可为空白
     * @param agentId         被委派到的 agent 标识，不可为空白
     * @param sessionId       本 run 的会话标识，不可为空白
     * @param toolCallId      关联的工具调用标识，可为 {@code null}
     * @throws JellyfishException 必填项为空白时抛出
     */
    public AgentRunRequest(String parentSessionId, String agentId, String sessionId, String toolCallId) {
        if (StringUtils.isBlank(parentSessionId)) {
            throw new JellyfishException("parentSessionId must not be blank");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new JellyfishException("agentId must not be blank");
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new JellyfishException("sessionId must not be blank");
        }
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.toolCallId = toolCallId;
    }

    /**
     * 获取父会话标识。
     *
     * @return 父会话标识
     */
    public String getParentSessionId() {
        return parentSessionId;
    }

    /**
     * 获取被委派到的 agent 标识。
     *
     * @return agent 标识
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取本 run 的会话标识。
     *
     * @return 会话标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取关联的工具调用标识。
     *
     * @return 工具调用标识；不是由 {@code task} 触发时为 {@code null}
     */
    public String getToolCallId() {
        return toolCallId;
    }

    @Override
    public String toString() {
        return "AgentRunRequest{agent=" + agentId + ", parentSession=" + parentSessionId + '}';
    }
}
