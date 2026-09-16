package zcd.jellyfish.plugin.compact;

import zcd.jellyfish.api.extension.CompactionStrategy;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.api.extension.ExtensionHandler;

/**
 * 压缩策略贡献：把本插件的摘要指令与两个数量参数交给内核。
 * <p>
 * <b>怎么做</b>：{@link CompactionStrategyRequest} 只带数字与标识（会话标识、触发原因、消息总数、已压
 * 条数、内核缺省值、token 预算、模型名），本处理器<b>逐字段决定要不要覆盖</b>——想覆盖就填，不想覆盖就留
 * {@code null} 让内核用缺省值。它拿不到任何一条消息正文，因此无法「只压某几条」或按内容改范围；
 * 那是对的：范围该由「保留多少条最新原文」这一个数字表达。
 * <p>
 * <b>为什么不做任何 I/O</b>：本处理器在同步派发路径上被调用，自动压缩时每轮组装都可能问一次。摘要指令在
 * 启动期就读好了，这里只做内存拼接。硬约束：只读、快速、不发布事件。
 * <p>
 * <b>为什么不看 {@code trigger}</b>：手动与自动该压成什么样，本插件认为没有区别——两者要的都是「把更早的
 * 对话压成一份能接着用的摘要」。想知道触发原因的能力保留在请求里（比如「自动压缩时更保守一些」这类策略
 * 将来要写时不必再改 api），但现在不靠它分支。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class CompactionStrategyContribution implements ExtensionHandler<CompactionStrategyRequest, CompactionStrategy> {

    /** 摘要指令来源。 */
    private final SummaryPrompt prompt;

    /** 本插件配置。 */
    private final PluginConfig config;

    /**
     * 构造策略贡献处理器。
     *
     * @param prompt 摘要指令来源，不可为 {@code null}
     * @param config 本插件配置，不可为 {@code null}
     */
    CompactionStrategyContribution(SummaryPrompt prompt, PluginConfig config) {
        this.prompt = prompt;
        this.config = config;
    }

    @Override
    public CompactionStrategy handle(CompactionStrategyRequest request) {
        return new CompactionStrategy(prompt.text(), config.keepRecentMessages(), config.maxSummaryChars());
    }
}
