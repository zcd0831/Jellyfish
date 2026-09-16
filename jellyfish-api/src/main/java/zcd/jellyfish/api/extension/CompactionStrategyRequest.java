package zcd.jellyfish.api.extension;

/**
 * 压缩策略请求：内核在「真的要把这个会话压一次」之前构造，询问插件「这一次该怎么压」。
 * <p>
 * <b>为什么需要这个扩展点</b>：摘要质量是压缩唯一的真实痛点，而「什么该重点保留」只有插件知道——
 * 待办插件知道哪些事项在办、项目插件知道 {@code AGENTS.md} 里的约定、记忆插件知道哪些内容值得长期记。
 * 内核把这件事开放出来，压缩策略就不必为每一种偏好去改内核。
 * <p>
 * <b>插件只定策略，不做执行</b>：读消息、挑范围、发模型调用、校验摘要、推进边界、记用量全部仍在内核手里。
 * 因此本请求<b>只带数字与标识，不带任何一条消息正文</b>（{@link #getMessageCount()} 这类计数足以让插件
 * 按会话规模选择策略），返回的 {@link CompactionStrategy} 也只有措辞与参数——没有「删哪条」这种字段。
 * 这是刻意的边界：插件拿不到会话内容，也无法让内核多发一次模型调用。
 * <p>
 * <b>什么时候会被问</b>：只有真要压的时候问一次（包括 {@code preview}，因为预览与执行必须给出同一个答案）。
 * 没注册处理器时内核用 {@code react} 段的缺省值，行为与没有这个扩展点时完全一致。
 * <p>
 * <b>实现约定</b>：本请求由内核在<b>调用点线程</b>同步派发，因此处理器必须<b>只读且快</b>——不要做 I/O、
 * 不要阻塞、不要发布事件。任何抛出异常的处理器都只被记一条 WARN 并跳过，其余处理器照常参与。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CompactionStrategyRequest extends ExtensionRequest<CompactionStrategy> {

    /** 触发原因。 */
    private final CompactionTrigger trigger;

    /** 会话当前的消息总条数。 */
    private final int messageCount;

    /** 已经被摘要覆盖的条数（{@code 0} 表示从未压过）。 */
    private final int compressedCount;

    /** 配置里的缺省保留条数（插件要覆盖的就是它）。 */
    private final int defaultKeepRecentMessages;

    /** 配置里的缺省摘要长度上限（字符数）。 */
    private final int defaultMaxSummaryChars;

    /** 本次摘要输入的 token 预算。 */
    private final long budgetTokens;

    /** 会话当前模型标识。 */
    private final String modelId;

    /**
     * 构造压缩策略请求。
     * <p>
     * <b>为什么带的是「缺省值」而不是「候选条数」</b>：候选条数取决于 {@link #getKeepRecentMessages()}
     * 之外的保留条数，而那个值正是本请求要问插件的东西——先算候选取值就只能用缺省保留条数算，
     * 插件据此做的判断立刻变成建立在另一个世界里的判断。给出缺省参数则没有这个循环：插件知道
     * 自己要覆盖的是什么，内核拿到答复后再算范围。
     *
     * @param sessionId                  会话标识，可为 {@code null}
     * @param trigger                    触发原因，不可为 {@code null}
     * @param messageCount               消息总条数
     * @param compressedCount            已被摘要覆盖的条数
     * @param defaultKeepRecentMessages  配置里的缺省保留条数
     * @param defaultMaxSummaryChars     配置里的缺省摘要长度上限
     * @param budgetTokens               摘要输入的 token 预算
     * @param modelId                    会话当前模型标识，可为 {@code null}
     */
    public CompactionStrategyRequest(String sessionId, CompactionTrigger trigger, int messageCount,
                                     int compressedCount, int defaultKeepRecentMessages,
                                     int defaultMaxSummaryChars, long budgetTokens, String modelId) {
        super(CompactionStrategy.class, sessionId);
        this.trigger = trigger;
        this.messageCount = messageCount;
        this.compressedCount = compressedCount;
        this.defaultKeepRecentMessages = defaultKeepRecentMessages;
        this.defaultMaxSummaryChars = defaultMaxSummaryChars;
        this.budgetTokens = budgetTokens;
        this.modelId = modelId;
    }

    @Override
    public String getRouteKey() {
        // 类型级扩展点：同一会话允许多个插件各给一部分策略，由内核按 order 合并
        return null;
    }

    /**
     * 获取触发原因。
     *
     * @return 触发原因，保证非 {@code null}
     */
    public CompactionTrigger getTrigger() {
        return trigger;
    }

    /**
     * 获取消息总条数。
     *
     * @return 消息条数
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * 获取已被摘要覆盖的条数。
     *
     * @return 条数，{@code 0} 表示从未压过
     */
    public int getCompressedCount() {
        return compressedCount;
    }

    /**
     * 获取配置里的缺省保留条数。
     *
     * @return 条数，非负
     */
    public int getDefaultKeepRecentMessages() {
        return defaultKeepRecentMessages;
    }

    /**
     * 获取配置里的缺省摘要长度上限。
     *
     * @return 字符数
     */
    public int getDefaultMaxSummaryChars() {
        return defaultMaxSummaryChars;
    }

    /**
     * 获取摘要输入的 token 预算。
     *
     * @return 预算 token 数
     */
    public long getBudgetTokens() {
        return budgetTokens;
    }

    /**
     * 获取会话当前模型标识。
     *
     * @return 模型标识，可能为 {@code null}
     */
    public String getModelId() {
        return modelId;
    }
}
