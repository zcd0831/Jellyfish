package zcd.jellyfish.infra.llm;

/**
 * 一次流式调用的句柄，用于取消请求。
 *
 * @author zcd
 */
public interface LlmStreamHandle {

    /**
     * 取消流式请求。取消后监听器会收到 {@link LlmStreamListener#onCancelled()}。
     */
    void cancel();
}
