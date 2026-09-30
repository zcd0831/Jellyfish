package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 缓存前缀断裂事件：一次请求的「可缓存前缀」与上一次不连续，缓存因此从断裂点起全部失效。
 * <p>
 * <b>为什么需要它</b>：厂商的 prompt 缓存是前缀匹配，改一个字节则其后全部按未命中价重算，
 * 而这件事此前<b>完全静默</b>——用户只在账单上看到它，等到那时已无从知道是哪一处改的。
 * 内核自己会把结论记进日志，但日志不能被程序消费：插件因此无法据此做守卫、告警或趋势统计。
 * 本事件把那件事放到可订阅的通道上。
 * <p>
 * <b>为什么只报「从哪一层开始断」而不是「哪几层变了」</b>：断裂是<b>累积</b>的——第 0 个 token
 * 变了，它后面的内容再变不变都无所谓，整段请求本来就要重算。因此缓存的真实语义只有一个断点：
 * <b>第一处不连续的位置</b>。报「哪几层变了」会把一个已经作废的观测混进来，订阅方还得自己去
 * 推出谁是第一个。
 * <p>
 * <b>它按轮发出，不做节流</b>：日志里的 WARN 是节流的（断裂持续时只报一次），因为逐轮刷屏会让
 * 日志不可读；但事件不同——如果下一轮的前缀<b>仍然</b>与这一轮不连续，那就是又一次真实的缓存损失，
 * 计数型订阅方需要看到每一次。因此本事件与 WARN 的节流口径刻意不同。
 * <p>
 * <b>首次观察不发</b>：那个会话还没有基线，无从判断有没有断。
 * <p>
 * <b>载荷是 api 侧值类型</b>：订阅方（含插件）不需要依赖内核的观察器实现。
 *
 * @author zcd
 */
public final class CachePrefixChangedEvent extends AbstractJellyfishEvent {

    /**
     * 断裂发生在哪一层前缀。枚举顺序即优先级：越靠前越靠近第 0 个 token。
     */
    public enum Layer {

        /**
         * system prompt：请求的第 0 个 token，一变则整段请求失效。
         * <p>
         * <b>正常情况下不该看到它</b>：摘要已挪进消息区、易变状态走回合上下文，能进来的只剩
         * agent 提示词与插件贡献块，两者都不随轮次变。收到本层意味着<b>应当按 bug 查</b>。
         */
        SYSTEM_PROMPT,

        /** 工具清单：在多数厂商的模板里排在 messages 之前，一变同样作废整段。 */
        TOOLS,

        /** 历史消息：被改写（压缩边界前移、旧工具结果被老化），改动点之后的内容全部失效。 */
        HISTORY
    }

    /** 断裂从哪一层开始。 */
    private final Layer layer;

    /** 与上一次逐条相同的消息数。 */
    private final int reusableMessages;

    /** 上一次请求的消息数。 */
    private final int previousMessages;

    /** 本次请求的消息数。 */
    private final int currentMessages;

    /** system prompt 的长度变化（字符数），仅当 {@link #getLayer()} 为 system prompt 时有意义。 */
    private final int systemPromptLengthDelta;

    /**
     * 构造缓存前缀断裂事件。
     *
     * @param sessionId               会话标识，可为 {@code null}
     * @param layer                   断裂从哪一层开始，不可为 {@code null}
     * @param reusableMessages        可复用的消息数
     * @param previousMessages        上一次请求的消息数
     * @param currentMessages         本次请求的消息数
     * @param systemPromptLengthDelta system prompt 的长度变化
     */
    public CachePrefixChangedEvent(String sessionId, Layer layer, int reusableMessages, int previousMessages,
                                   int currentMessages, int systemPromptLengthDelta) {
        super(sessionId);
        this.layer = layer;
        this.reusableMessages = reusableMessages;
        this.previousMessages = previousMessages;
        this.currentMessages = currentMessages;
        this.systemPromptLengthDelta = systemPromptLengthDelta;
    }

    /**
     * 获取断裂起始层。
     *
     * @return 断裂起始层，保证非 {@code null}
     */
    public Layer getLayer() {
        return layer;
    }

    /**
     * 获取可复用的消息数，从头部起算。
     *
     * @return 可复用消息数
     */
    public int getReusableMessages() {
        return reusableMessages;
    }

    /**
     * 获取上一次请求的消息数。
     *
     * @return 消息数
     */
    public int getPreviousMessages() {
        return previousMessages;
    }

    /**
     * 获取本次请求的消息数。
     *
     * @return 消息数
     */
    public int getCurrentMessages() {
        return currentMessages;
    }

    /**
     * 获取 system prompt 的长度变化。
     *
     * @return 变化量（字符数），正数表示变长
     */
    public int getSystemPromptLengthDelta() {
        return systemPromptLengthDelta;
    }

    @Override
    public String toString() {
        return "CachePrefixChangedEvent{layer=" + layer + ", reusable=" + reusableMessages + '/'
                + previousMessages + ", current=" + currentMessages + ", promptDelta="
                + systemPromptLengthDelta + '}';
    }
}
