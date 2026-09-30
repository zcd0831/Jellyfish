package zcd.jellyfish.api.llm;

/**
 * 一次 LLM 调用的传输实现：把内核给出的<b>厂商无关请求</b>送到目标端点，并把响应以事件形式回吐。
 * <p>
 * <b>解决什么问题</b>：内核此前只认 {@code models.json} 里 {@code type} 指向内置客户端的那几种厂商，
 * 于是本地 llama.cpp、企业自建网关、私有协议、自定义鉴权全都无路可走——它们要么改内核代码，
 * 要么不存在。本接口是插件接管这一层的正式入口：注册它（见
 * {@link zcd.jellyfish.api.extension.ProviderRegistrationRequest}），用户就能在
 * {@code models.json} 里写一个指向该类型的 provider。
 * <p>
 * <b>它到不了内核的序列化逻辑</b>：契约的两端是「厂商无关的请求」与「厂商格式的 HTTP」，
 * 插件不能改写内核如何组装提示词、如何切分消息、如何做缓存断点。那些是前缀不变量的组成部分，
 * 一旦外放，「同一份历史在任何厂商上等价」就没有人保证了。
 * <p>
 * <b>{@link #send} 是阻塞的</b>：返回时全部事件都已交给监听器，且最后一个事件一定是终止事件之一
 * （{@link LlmTransportListener#onComplete} / {@link LlmTransportListener#onError} /
 * {@link LlmTransportListener#onCancelled}）。内核据此把同步调用直接内联执行、把流式调用放到线程池上，
 * 因此<b>不存在「等多久算超时」这个新配置项</b>，也不存在「插件不回调就永久挂住」的悬空态。
 * <p>
 * <b>取消是协作式的</b>：令牌在 {@link LlmTransportRequest#getCancellationToken()}，插件应当在开始
 * 阻塞之前用 {@code onCancel} 登记「中断自己的 HTTP 调用」这个动作。内核的取消只是把标志置上并停止
 * 投递后续事件——一个既不轮询令牌也不登记回调的插件，会一直跑到它自己的超时为止。
 * <p>
 * <b>失败要复用内核的异常类型</b>：端点以非 2xx 拒绝时抛
 * {@link zcd.jellyfish.api.LlmHttpException}（带上状态码），而不是自造一套——内核靠状态码区分
 * 「字段被拒该降级」与「限流该重试」，这两条路的处理完全相反。
 * <p>
 * <b>实现必须是线程安全的</b>：同一个实例会服务该类型下的全部 provider，并可能被多个会话并发调用。
 *
 * @author zcd
 */
public interface LlmTransport {

    /**
     * 发送一次请求，并把响应事件交给监听器。
     * <p>
     * 本方法阻塞到本次调用结束（终止事件已投递）为止。
     *
     * @param request  厂商无关的请求，不可为 {@code null}
     * @param listener 事件监听器，不可为 {@code null}
     */
    void send(LlmTransportRequest request, LlmTransportListener listener);
}
