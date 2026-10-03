package zcd.jellyfish.server.dto;

import zcd.jellyfish.api.extension.SessionUsageSnapshot;

/**
 * 会话摘要：{@code GET /sessions} 的列表项。
 * <p>
 * <b>为什么不直接返回完整快照</b>：列表页面只需要「哪几个会话、叫什么、绑了谁」，而完整快照含全部消息，
 * 会话一多就会把几十兆正文一次性发给前端。列表和详情分成两种形状是接口设计，不是优化。
 * <p>
 * 用量直接复用 api 侧 {@link SessionUsageSnapshot}：它已经是跨边界的稳定投影，再包一层
 * 只会多一个与内核同步维护的字段镜像。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionSummary {

    /** 会话标识。 */
    private final String sessionId;

    /** 会话标题，可为 {@code null}。 */
    private final String title;

    /** 绑定的 agentId，可为 {@code null}。 */
    private final String agentId;

    /** provider 名，可为 {@code null}。 */
    private final String provider;

    /** 模型名，可为 {@code null}。 */
    private final String model;

    /** 创建时间戳（epoch millis）。 */
    private final long createdAt;

    /** 最后变更时间戳（epoch millis）。 */
    private final long updatedAt;

    /** 消息条数。 */
    private final int messageCount;

    /** 累计用量。 */
    private final SessionUsageSnapshot usage;

    /**
     * 构造会话摘要。
     *
     * @param sessionId      会话标识
     * @param title          标题，可为 {@code null}
     * @param agentId        agentId，可为 {@code null}
     * @param provider       provider 名，可为 {@code null}
     * @param model          模型名，可为 {@code null}
     * @param createdAt      创建时间戳
     * @param updatedAt      最后变更时间戳
     * @param messageCount   消息条数
     * @param usage          累计用量
     */
    public SessionSummary(String sessionId, String title, String agentId, String provider, String model,
                          long createdAt, long updatedAt, int messageCount,
                          SessionUsageSnapshot usage) {
        this.sessionId = sessionId;
        this.title = title;
        this.agentId = agentId;
        this.provider = provider;
        this.model = model;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messageCount = messageCount;
        this.usage = usage;
    }

    /**
     * 获取会话标识。
     *
     * @return 会话标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取会话标题。
     *
     * @return 标题，可能为 {@code null}
     */
    public String getTitle() {
        return title;
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
     * 获取 provider 名。
     *
     * @return provider 名，可能为 {@code null}
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 获取模型名。
     *
     * @return 模型名，可能为 {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取创建时间戳。
     *
     * @return 创建时间戳（epoch millis）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取最后变更时间戳。
     *
     * @return 最后变更时间戳（epoch millis）
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 获取消息条数。
     *
     * @return 消息条数
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * 获取累计用量。
     *
     * @return 累计用量
     */
    public SessionUsageSnapshot getUsage() {
        return usage;
    }
}
