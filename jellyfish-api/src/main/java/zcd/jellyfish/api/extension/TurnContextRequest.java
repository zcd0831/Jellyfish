package zcd.jellyfish.api.extension;

/**
 * 回合上下文请求：内核在把用户本轮输入写进会话<b>之前</b>构造，询问「有没有要随这条消息一起送达的即时状态」。
 * <p>
 * <b>为什么需要这个扩展点</b>：插件手上的状态（待办清单、召回的记忆……）模型必须看得见，而
 * {@link PromptContributionRequest} 是它此前唯一的去处——那会把状态放进 system prompt，
 * 也就是缓存前缀的第 0 个 token。于是「待办状态变了一次」这件事的代价是<b>整个请求从第 0 个 token
 * 起全部失效</b>。本扩展点给这类状态一条 append-only 的通道：产物随本轮用户消息落盘，
 * 只影响本轮新产生的 token。
 * <p>
 * <b>两者怎么选</b>：
 * <ul>
 *     <li><b>会话内不变</b>（项目约定、skills 清单）→ {@link PromptContributionRequest}
 *     配 {@link PromptPlacement#SESSION}，它属于前缀里可以被反复复用的那一段；</li>
 *     <li><b>会话内会变</b>（待办进度、召回的记忆）→ 本扩展点。放进 system prompt 会让每次变化
 *     作废整段请求，而那正是最贵的一种改动。</li>
 * </ul>
 * <p>
 * <b>每轮都问，且不做去重</b>：看起来「状态没变就不用再送」更省，但那会引入一个难查的失败模式——
 * 上一次注入的内容可能已经落进被压缩掉的那一段，于是模型从此再也看不到这份状态。
 * 而这些块通常只有几行、又落在已缓存的前缀之后，重复送的代价远小于「模型不知道自己在做什么」。
 * 需不需要去重由插件自己判断（{@link #getUserInput()} 就是给它这个判断用的）。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：同一个回合允许多个插件各贡献一段，
 * 内核按 {@code order} 升序依次询问后拼接。
 * <p>
 * 不经此处的插件不受影响：没有处理器时用户消息原样下发。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnContextRequest extends ExtensionRequest<TurnContext> {

    /** 本轮用户输入原文。 */
    private final String userInput;

    /** 是否嵌套回合（子代理）。 */
    private final boolean nested;

    /**
     * 构造回合上下文请求。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param userInput 本轮用户输入原文，可为 {@code null}（等价空串）
     * @param nested    是否嵌套回合
     */
    public TurnContextRequest(String sessionId, String userInput, boolean nested) {
        super(TurnContext.class, sessionId);
        this.userInput = userInput == null ? "" : userInput;
        this.nested = nested;
    }

    @Override
    public String getRouteKey() {
        return null;
    }

    /**
     * 获取本轮用户输入原文。
     * <p>
     * 给插件用来判断「这条状态这一轮到底要不要说」——例如用户没提待办、状态也没变，就不必送。
     *
     * @return 本轮用户输入，保证非 {@code null}（未提供时为空串）
     */
    public String getUserInput() {
        return userInput;
    }

    /**
     * 判断这是不是嵌套回合。
     * <p>
     * 子代理与主会话的输入来源不同（前者是父代理写的任务原文），注入的内容与措辞可能要区别对待。
     *
     * @return 嵌套回合返回 {@code true}
     */
    public boolean isNested() {
        return nested;
    }
}
