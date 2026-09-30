package zcd.jellyfish.api.llm;

/**
 * 传输层的事件回调：与内核的流式监听器逐方法对应，插件只需要把自家协议的增量译过来。
 * <p>
 * <b>所有方法都是默认空实现</b>，插件只需覆盖关心的事件。
 * <p>
 * <b>约定</b>：
 * <ul>
 *     <li>{@link #onText} / {@link #onReasoning} 收到的是<b>增量片段</b>（delta），不是全量文本；</li>
 *     <li>{@link #onToolCall} 可能被多次触发，每次给的是<b>当前累计到的快照</b>——分片归属靠
 *     {@link LlmTransportToolCall#getIndex()}。最终完整结果以 {@link #onComplete} 里的
 *     {@link LlmTransportResponse#getToolCalls()} 为准；</li>
 *     <li>{@link #onComplete} 与 {@link #onCancelled} / {@link #onError} <b>互斥</b>，且三者
 *     合起来<b>恰好触发一次</b>：{@link LlmTransport#send} 返回之前必须已经投递了其中一个。
 *     一个都没投递等于让内核无从判断这次调用结束了没有，内核会按失败处理并如实报告；</li>
 *     <li>终止事件之后投递的任何事件都被忽略。</li>
 * </ul>
 * <p>
 * <b>投递在插件自己的线程上完成</b>：内核不做缓冲、不切换线程，因此插件不必关心「监听器是不是线程安全的」，
 * 只要按协议顺序投递即可；但监听器本身因此也<b>必须快</b>——内核侧的实现只做转发与计数，不做 I/O。
 *
 * @author zcd
 */
public interface LlmTransportListener {

    /**
     * 连接建立、开始接收数据时回调一次。
     */
    default void onOpen() {
    }

    /**
     * 收到一段文本增量。
     *
     * @param delta 本次新增的文本片段
     */
    default void onText(String delta) {
    }

    /**
     * 收到一段思考过程增量。
     *
     * @param delta 本次新增的思考过程片段
     */
    default void onReasoning(String delta) {
    }

    /**
     * 收到工具调用信息，可能被多次触发。
     *
     * @param toolCall 当前累计到的工具调用快照
     */
    default void onToolCall(LlmTransportToolCall toolCall) {
    }

    /**
     * 调用正常结束。
     *
     * @param response 本次调用的完整结果
     */
    default void onComplete(LlmTransportResponse response) {
    }

    /**
     * 调用被取消。
     */
    default void onCancelled() {
    }

    /**
     * 调用失败，与 {@link #onComplete} / {@link #onCancelled} 互斥。
     *
     * @param error 失败原因
     */
    default void onError(Throwable error) {
    }
}
