package zcd.jellyfish.core.prompt;

import zcd.jellyfish.infra.llm.LlmRequest;

/**
 * 一次请求组装的结果：请求本身 + 它的上下文用量。
 * <p>
 * <b>为什么把两者绑在一起返回</b>：用量只有组装过程算得出来（它知道裁剪掉了什么、system prompt
 * 有多大），而它唯一的消费者就在组装的下一步（压缩的触发判定）。让调用方自己再算一遍，就等于把
 * 「压缩判据」与「裁剪判据」拆成两个会漂移的公式。
 * <p>
 * <b>为什么不用可变 holder</b>：组装发生在 {@code react} 线程上、结果会立刻被读走，
 * 一个不可变值对象比「先 new 再填」少一类半成品状态。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PromptAssembly {

    /** 组装好的请求。 */
    private final LlmRequest request;

    /** 本次请求的上下文用量。 */
    private final ContextUsage usage;

    /**
     * 构造组装结果。
     *
     * @param request 组装好的请求，不可为 {@code null}
     * @param usage   上下文用量，不可为 {@code null}
     */
    PromptAssembly(LlmRequest request, ContextUsage usage) {
        this.request = request;
        this.usage = usage;
    }

    /**
     * 获取组装好的请求。
     *
     * @return 请求，保证非 {@code null}
     */
    public LlmRequest getRequest() {
        return request;
    }

    /**
     * 获取上下文用量。
     *
     * @return 用量，保证非 {@code null}
     */
    public ContextUsage getUsage() {
        return usage;
    }
}
