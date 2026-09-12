package zcd.jellyfish.infra.llm;

/**
 * 流式响应回调。
 * <p>
 * 所有方法均为默认空实现，调用方只需覆盖关心的事件。
 * <p>
 * 约定：
 * <ul>
 *     <li>{@link #onText(String)} / {@link #onThinking(String)} 收到的是增量片段（delta），不是全量文本。</li>
 *     <li>{@link #onToolCall(LlmToolCall)} 可能被多次触发：OpenAI 兼容协议会分片下发参数，
 *     每次触发返回的是当前累计到的调用快照；Claude 在参数完整后一次性下发。最终完整结果以
 *     {@link #onComplete(LlmResponse)} 中的 {@link LlmResponse#getToolCalls()} 为准。</li>
 *     <li>{@link #onComplete(LlmResponse)} 在正常结束时触发一次；被取消时触发 {@link #onCancelled()}。</li>
 *     <li>{@link #onError(Throwable)} 与 {@link #onComplete(LlmResponse)} / {@link #onCancelled()} 互斥。</li>
 * </ul>
 *
 * @author zcd
 */
public interface LlmStreamListener {

    /**
     * 连接建立、开始接收流数据时回调一次。
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
    default void onThinking(String delta) {
    }

    /**
     * 收到工具调用信息，可能被多次触发。
     *
     * @param toolCall 当前累计到的工具调用快照
     */
    default void onToolCall(LlmToolCall toolCall) {
    }

    /**
     * 流正常结束，返回聚合后的完整结果。
     *
     * @param response 本次调用的完整结果
     */
    default void onComplete(LlmResponse response) {
    }

    /**
     * 流被调用方取消。
     */
    default void onCancelled() {
    }

    /**
     * 流处理失败，与 {@link #onComplete(LlmResponse)} / {@link #onCancelled()} 互斥。
     *
     * @param error 失败原因
     */
    default void onError(Throwable error) {
    }
}
