package zcd.jellyfish.core.subagent;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import org.apache.commons.lang3.StringUtils;

/**
 * 一次子代理委派的输入：委派方是谁、委派给谁、任务是什么、怎么取消。
 * <p>
 * <b>刻意不含模型、工具清单与提示词</b>：那三样都由被委派的 {@code agentId} 指向的
 * {@code AgentDefinition} 决定（{@code model} 字段、{@code permissions}、同目录的 {@code .md}）。
 * 让调用方在这里再传一遍，就等于把「这个 agent 用什么模型、有哪些工具」这句话说了两次，
 * 而两次说法必然会漂移。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SubAgentCall {

    /** 派生这次委派的父会话标识。 */
    private final String parentSessionId;

    /** 被委派到的 agent 标识。 */
    private final String agentId;

    /** 给子代理的任务原文。 */
    private final String prompt;

    /** 父回合的取消令牌。 */
    private final CancellationToken cancellationToken;

    /**
     * 构造一次委派请求。
     *
     * @param parentSessionId   父会话标识，不可为空白
     * @param agentId           被委派到的 agent 标识，不可为空白
     * @param prompt            给子代理的任务原文，可为 {@code null}
     * @param cancellationToken 父回合的取消令牌，可为 {@code null}（按不取消处理）
     * @throws JellyfishException 父会话标识或 agent 标识为空白时抛出
     */
    public SubAgentCall(String parentSessionId, String agentId, String prompt,
                        CancellationToken cancellationToken) {
        if (StringUtils.isBlank(parentSessionId)) {
            throw new JellyfishException("parentSessionId must not be blank");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new JellyfishException("agentId must not be blank");
        }
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.prompt = prompt;
        this.cancellationToken = cancellationToken == null ? CancellationToken.NONE : cancellationToken;
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
     * 获取给子代理的任务原文。
     *
     * @return 任务原文，可能为 {@code null}
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * 获取父回合的取消令牌。
     *
     * @return 取消令牌，保证非 {@code null}
     */
    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    /**
     * 判断任务原文是否为空。
     * <p>
     * 空任务不该被派出去：子代理看不到任何指令，只会看到自己的系统提示词，
     * 然后基于零信息开始工作——那既费钱又几乎注定是无用功。
     *
     * @return 任务原文为空白返回 {@code true}
     */
    public boolean hasBlankPrompt() {
        return StringUtils.isBlank(prompt);
    }

    @Override
    public String toString() {
        // 任务原文可能很长且含敏感内容，诊断输出只带标识
        return "SubAgentCall{parent=" + parentSessionId + ", agent=" + agentId + '}';
    }
}
