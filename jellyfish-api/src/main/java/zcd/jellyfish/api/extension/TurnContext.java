package zcd.jellyfish.api.extension;

/**
 * 回合上下文结果：要随本轮用户消息一起送达的一段即时状态。
 * <p>
 * <b>与 {@link PromptContribution} 的分工</b>：那个进 system prompt——也就是缓存前缀的第 0 个 token，
 * 改一次就把整个请求作废；本类是<b>随本轮用户消息落盘</b>的，因此只影响本轮新产生的 token，
 * 对之前已经发送过的全部内容没有任何影响。
 * <p>
 * <b>为什么是「拼进用户消息」而不是「插入一条消息」</b>：会话的消息序列是「已经发生过的事」的账本，
 * 往里插一条谁也没说过的消息会让屏幕投影、{@code /resume} 与 token 统计一起失真。拼进本轮用户消息
 * 则让这件事变成「用户在说这句话时顺带提了一句」，账本天然自洽。
 * <p>
 * 返回空结果是正当用法：插件每轮都会被问到，只有在真的有话要说时才返回文本。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnContext {

    /** 空结果：本轮没有要送达的即时状态。 */
    private static final TurnContext EMPTY = new TurnContext(null);

    /** 上下文文本，无内容时为 {@code null}。 */
    private final String text;

    /**
     * 构造结果。
     *
     * @param text 上下文文本，可为 {@code null}
     */
    private TurnContext(String text) {
        this.text = text;
    }

    /**
     * 构造结果；文本为空白时等价于 {@link #empty()}。
     *
     * @param text 上下文文本，可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static TurnContext of(String text) {
        return text == null || text.trim().isEmpty() ? EMPTY : new TurnContext(text);
    }

    /**
     * 构造空结果。
     *
     * @return 没有内容的回合上下文
     */
    public static TurnContext empty() {
        return EMPTY;
    }

    /**
     * 获取上下文文本。
     *
     * @return 上下文文本，无内容时为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 判断是否没有内容。
     *
     * @return 无内容返回 {@code true}
     */
    public boolean isEmpty() {
        return text == null;
    }

    @Override
    public String toString() {
        // 刻意不打印正文：它可能是整段上下文，混进日志行会很难看
        return "TurnContext{length=" + (text == null ? 0 : text.length()) + '}';
    }
}
