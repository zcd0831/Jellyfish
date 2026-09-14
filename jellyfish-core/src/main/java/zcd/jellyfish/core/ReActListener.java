package zcd.jellyfish.core;

/**
 * ReAct 回合的流式回调。
 * <p>
 * <b>线程语义</b>：一次回合的全部回调都在同一个 {@code react} 线程上触发，因此实现方不需要自己加锁，
 * 也不会出现「文本增量与工具事件乱序」。代价是回调中做耗时操作会拖住整条循环，需要异步处理的调用方
 * 应在实现里自行转投队列。
 * <p>
 * 所有方法都是默认空实现：只想拿最终结果的调用方可以只覆盖 {@link #onComplete(ReActResult)}，
 * 或者直接用 {@link #NOOP} 配合 {@link ReActTurn#await()}。
 *
 * @author zcd
 */
public interface ReActListener {

    /** 什么都不做的监听器，供同步调用方使用。 */
    ReActListener NOOP = new ReActListener() {
    };

    /**
     * 收到一段模型文本增量。
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
     * 一次工具调用开始。
     *
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     */
    default void onToolCallStarted(String toolCallId, String toolName) {
    }

    /**
     * 一次工具调用结束（成功或失败都会回调）。
     *
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param success    是否成功
     * @param output     结果文本（失败时为错误说明）
     */
    default void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output) {
    }

    /**
     * 回合正常结束（含达到最大轮次），携带最终结果。
     *
     * @param result 回合结果
     */
    default void onComplete(ReActResult result) {
    }

    /**
     * 回合被调用方取消。
     */
    default void onCancelled() {
    }

    /**
     * 回合因异常终止，与 {@link #onComplete(ReActResult)} / {@link #onCancelled()} 互斥。
     *
     * @param error 失败原因
     */
    default void onError(Throwable error) {
    }
}
