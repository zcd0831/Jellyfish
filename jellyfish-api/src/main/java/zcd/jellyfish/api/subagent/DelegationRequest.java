package zcd.jellyfish.api.subagent;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

/**
 * 一次委派请求：让某个子代理去做一件事。
 * <p>
 * <b>它刻意不带「怎么跑」的字段</b>：模型、轮数上限、工具清单全部由被委派到的那个 agent
 * 自己的配置决定，调用方无从干预（与 {@code task} 工具同口径）。子代理与主会话相互隔离是这块能力
 * 的设计前提，不是可调参数。
 * <p>
 * <b>取消令牌要传父回合那一个</b>：编排方通常从 {@code ToolCallRequest.getCancellationToken()} 取到它。
 * 传 {@code null} 等价于「不可取消」，因此只有在你确实不关心父回合中断时才省略。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class DelegationRequest {

    /** 父会话标识：子代理的结果与用量最终归到这个会话上。 */
    private final String parentSessionId;

    /** 被委派到的 agent 标识。 */
    private final String agentId;

    /** 给子代理的任务原文；它看不到本次对话，只有这段文字。 */
    private final String prompt;

    /** 父回合的取消令牌。 */
    private final CancellationToken cancellationToken;

    /**
     * 构造委派请求。
     *
     * @param parentSessionId   父会话标识，不可为空白
     * @param agentId           被委派到的 agent 标识，不可为空白
     * @param prompt            给子代理的任务原文，可为 {@code null}
     * @param cancellationToken 父回合的取消令牌，可为 {@code null}（按不可取消处理）
     * @throws JellyfishException 父会话标识或 agent 标识为空白时抛出
     */
    public DelegationRequest(String parentSessionId, String agentId, String prompt,
                             CancellationToken cancellationToken) {
        if (parentSessionId == null || parentSessionId.trim().isEmpty()) {
            throw new JellyfishException("parentSessionId must not be blank");
        }
        if (agentId == null || agentId.trim().isEmpty()) {
            throw new JellyfishException("agentId must not be blank");
        }
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.prompt = prompt;
        this.cancellationToken = cancellationToken == null ? CancellationToken.NONE : cancellationToken;
    }

    /**
     * 构造一个不可取消的委派请求。
     *
     * @param parentSessionId 父会话标识，不可为空白
     * @param agentId         被委派到的 agent 标识，不可为空白
     * @param prompt          给子代理的任务原文，可为 {@code null}
     * @return 请求，保证非 {@code null}
     * @throws JellyfishException 父会话标识或 agent 标识为空白时抛出
     */
    public static DelegationRequest of(String parentSessionId, String agentId, String prompt) {
        return new DelegationRequest(parentSessionId, agentId, prompt, null);
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
     * 获取任务原文。
     *
     * @return 任务原文，可能为 {@code null}
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * 获取取消令牌。
     *
     * @return 取消令牌，保证非 {@code null}（未提供时为 {@link CancellationToken#NONE}）
     */
    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    @Override
    public String toString() {
        return "DelegationRequest{parent=" + parentSessionId + ", agent=" + agentId + '}';
    }
}
