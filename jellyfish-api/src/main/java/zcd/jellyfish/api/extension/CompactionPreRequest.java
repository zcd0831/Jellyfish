package zcd.jellyfish.api.extension;

/**
 * 压缩前请求：内核在<b>选定压缩范围之后、发起摘要模型调用之前</b>询问「这次要不要压、压多少」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：只要装了插件，每一次压缩
 * 都会问一遍，不按会话或触发方式分槽。
 * <p>
 * <b>为什么调用点必须在这里</b>：这是整条压缩链上唯一「还没花钱」的位置。摘要请求一旦发出去，
 * token 就已经花掉了，之后再拦只是浪费一次调用。
 * <p>
 * <b>它能看到什么、看不到什么</b>：只给规模（条数、token 估算、保留条数、旧边界）与触发原因，
 * <b>不给消息正文</b>。理由是压缩策略扩展点（{@link CompactionStrategy}）早就立过这条边界——
 * 「怎么压」是策略，「这次要不要压」是钩子，两者都不该让插件开始理解对话内容。
 * 需要正文的插件请改用别的路径（例如从会话事件里自己攒）。
 * <p>
 * <b>失败语义由调用点决定</b>：handler 抛错按「放行」处理（记 WARN），压缩不该因为一个观察者坏了而失败。
 * <p>
 * <b>必须快且不得阻塞</b>：handler 在压缩的调用线程上同步执行，而 {@code /compact} 的预览路径
 * 也会走到这里（那是外壳线程）。因此它只能做纯计算，不得回调内核、不得发布事件。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CompactionPreRequest extends ExtensionRequest<CompactionDirective> {

    /** 触发原因。 */
    private final CompactionTrigger trigger;

    /** 当前会话的消息条数。 */
    private final int messageCount;

    /** 待压缩范围（在旧边界之后、保留段之前）的 token 估算值。 */
    private final int tokensBefore;

    /** 策略算完后的保留条数，也是本次插件可以改写的起点。 */
    private final int keepRecentMessages;

    /** 上一次压缩的边界消息标识，从未压缩过时为 {@code null}。 */
    private final String previousBoundaryMessageId;

    /**
     * 构造压缩前请求。
     * <p>
     * {@code sessionId} 同时传给基类：处理器可能被内核在任意线程调用，
     * 把会话标识放在请求对象上而不是依赖调用线程的 ThreadLocal。
     *
     * @param sessionId                 会话标识，不可为空白
     * @param trigger                   触发原因，可为 {@code null}（按 {@link CompactionTrigger#MANUAL} 处理）
     * @param messageCount              当前会话的消息条数
     * @param tokensBefore              待压缩范围的 token 估算值
     * @param keepRecentMessages        策略算完后的保留条数
     * @param previousBoundaryMessageId 上一次压缩的边界消息标识，可为 {@code null}
     */
    public CompactionPreRequest(String sessionId, CompactionTrigger trigger, int messageCount, int tokensBefore,
                               int keepRecentMessages, String previousBoundaryMessageId) {
        super(CompactionDirective.class, sessionId);
        this.trigger = trigger == null ? CompactionTrigger.MANUAL : trigger;
        this.messageCount = messageCount;
        this.tokensBefore = tokensBefore;
        this.keepRecentMessages = keepRecentMessages;
        this.previousBoundaryMessageId = previousBoundaryMessageId;
    }

    @Override
    public String getRouteKey() {
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
     * 获取当前会话的消息条数。
     *
     * @return 消息条数
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * 获取待压缩范围的 token 估算值。
     * <p>
     * <b>是估算而不是实测</b>：它按消息文本长度折算，不含 system prompt 与工具定义。
     * 用它做预算判断足够，但不要拿它与厂商返回的计费数对上。
     *
     * @return token 估算值
     */
    public int getTokensBefore() {
        return tokensBefore;
    }

    /**
     * 获取策略算完后的保留条数。
     * <p>
     * 这是插件改写的起点：返回 {@link CompactionDirective#keepRecent(int)} 会覆盖它，
     * 返回 {@link CompactionDirective#proceed()} 则原样沿用。
     *
     * @return 保留条数，保证非负
     */
    public int getKeepRecentMessages() {
        return keepRecentMessages;
    }

    /**
     * 获取上一次压缩的边界消息标识。
     *
     * @return 边界消息标识，从未压缩过时为 {@code null}
     */
    public String getPreviousBoundaryMessageId() {
        return previousBoundaryMessageId;
    }
}
