package zcd.jellyfish.infra.metrics;

/**
 * 指标名常量：集中一处，避免同一个计数在订阅方与展示方各写一遍字面量。
 * <p>
 * 命名采用 {@code 域.动作} 的小写点号风格（如 {@code command.executed}），与日志里的
 * {@code key=value} 风格区分开；仪表（gauge）同样风格（如 {@code eventChannel.queueSize}）。
 *
 * @author zcd
 */
public final class MetricNames {

    /** 命令分发总次数（含成功 / 失败 / 未知）。 */
    public static final String COMMAND_EXECUTED = "command.executed";

    /** 命令执行失败次数。 */
    public static final String COMMAND_ERROR = "command.error";

    /** 未知命令次数。 */
    public static final String COMMAND_UNKNOWN = "command.unknown";

    /** 工具调用发起次数。 */
    public static final String TOOL_STARTED = "tool.started";

    /** 工具调用成功次数。 */
    public static final String TOOL_COMPLETED = "tool.completed";

    /** 工具调用失败次数。 */
    public static final String TOOL_FAILED = "tool.failed";

    /** 权限判定放行次数。 */
    public static final String PERMISSION_ALLOWED = "permission.allowed";

    /** 权限判定拒绝次数。 */
    public static final String PERMISSION_DENIED = "permission.denied";

    /** 会话创建次数。 */
    public static final String SESSION_CREATED = "session.created";

    /** 会话关闭次数。 */
    public static final String SESSION_CLOSED = "session.closed";

    /** 会话压缩应用次数。 */
    public static final String COMPACTION_APPLIED = "compaction.applied";

    /** 压缩累计吸纳的消息条数。 */
    public static final String COMPACTION_COMPRESSED_MESSAGES = "compaction.compressedMessages";

    /** 压缩累计未收录（真正丢弃）的消息条数。 */
    public static final String COMPACTION_DROPPED_MESSAGES = "compaction.droppedMessages";

    /** 配置告警次数。 */
    public static final String CONFIG_WARNINGS = "config.warnings";

    /** 配置重载次数。 */
    public static final String CONFIG_RELOADS = "config.reloads";

    /** 配置重载累计重启的插件数。 */
    public static final String CONFIG_RELOAD_RESTARTED_PLUGINS = "config.reloadRestartedPlugins";

    /** 插件进入启动态的次数。 */
    public static final String PLUGIN_STARTED = "plugin.started";

    /** 插件进入停止态的次数。 */
    public static final String PLUGIN_STOPPED = "plugin.stopped";

    /** 插件进入失败态的次数。 */
    public static final String PLUGIN_FAILED = "plugin.failed";

    /** 模型调用累计次数（含未返回用量的调用）。 */
    public static final String LLM_CALLS = "llm.calls";

    /** 输入 token 累计总数（含缓存命中与建缓存的部分）。 */
    public static final String LLM_PROMPT_TOKENS = "llm.promptTokens";

    /** 输入中命中缓存的累计 token 数。 */
    public static final String LLM_CACHE_READ_TOKENS = "llm.cacheReadTokens";

    /** 输入中写入缓存的累计 token 数。 */
    public static final String LLM_CACHE_WRITE_TOKENS = "llm.cacheWriteTokens";

    /** 插件状态变更总次数。 */
    public static final String PLUGIN_STATE_CHANGES = "plugin.stateChanges";

    /** 外壳贡献：入队成功次数。 */
    public static final String PLUGIN_SHELL_CONTRIBUTION_ACCEPTED = "plugin.shellContribution.accepted";

    /** 外壳贡献：与同 owner + 同 key 的未交付项合并的次数。 */
    public static final String PLUGIN_SHELL_CONTRIBUTION_COALESCED = "plugin.shellContribution.coalesced";

    /**
     * 外壳贡献：未能入队的次数。
     * <p>
     * 把「队列满」「会话不存在」「本外壳不渲染」三档合在一处的理由：插件侧能采取的行动是同一个
     * ——什么都不做（丢弃不是失败，重发只会把一次洪水放大成持续洪水）。诊断要分档时看日志。
     */
    public static final String PLUGIN_SHELL_CONTRIBUTION_DROPPED = "plugin.shellContribution.dropped";

    /** 仪表：事件通道已发布通知数。 */
    public static final String EVENT_PUBLISHED = "eventChannel.published";

    /** 仪表：事件通道丢弃通知数。 */
    public static final String EVENT_DROPPED = "eventChannel.dropped";

    /** 仪表：事件通道无订阅者命中数。 */
    public static final String EVENT_UNMATCHED = "eventChannel.unmatched";

    /** 仪表：事件通道订阅者异常数。 */
    public static final String EVENT_SUBSCRIBER_ERRORS = "eventChannel.subscriberErrors";

    /** 仪表：事件通道广播线程活动数。 */
    public static final String EVENT_ACTIVE_THREADS = "eventChannel.activeThreads";

    /** 仪表：事件通道队列长度。 */
    public static final String EVENT_QUEUE_SIZE = "eventChannel.queueSize";

    /** 私有构造器：常量类不可实例化。 */
    private MetricNames() {
    }
}
