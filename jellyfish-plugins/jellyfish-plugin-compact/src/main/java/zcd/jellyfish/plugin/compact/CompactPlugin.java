package zcd.jellyfish.plugin.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 官方压缩插件：为内核的会话压缩提供<b>摘要策略</b>——摘要指令正文 + 两个数量参数。
 * <p>
 * <b>为什么压缩要插件化</b>：内核手里只有机制（读消息、选范围、发模型调用、校验摘要、推进边界、记用量、
 * 落盘），「这份历史压成什么样」是决定而非机制。放进插件有三个直接好处：摘要措辞可以自己改、保留条数可以
 * 按团队习惯调、不想用时直接不启用插件（压缩整体缺席，而不是有个半成品在内核里空转）。
 * <p>
 * <b>插件不做什么</b>（内核的机制边界，不是本插件的自觉）：拿不到任何一条消息正文、不能发起模型调用、
 * 没有否决权。它只回答「这一次该怎么压」——见 {@link CompactionStrategyContribution}。
 * <p>
 * <b>配置段可选</b>：{@code plugins.configurations.jellyfish-compact} 可调
 * {@code keepRecentMessages} / {@code maxSummaryChars}；整段留空则两项都用内核缺省值
 * （{@code react.compactKeepRecentMessages} 与 {@code react.compactMaxSummaryChars}），
 * 摘要指令仍是本插件自带的资源。
 * <p>
 * <b>摘要指令是本插件的资源</b>：{@code summary-prompt.md}（jar 根目录）。
 * 「资源跟着读者走」——读它的类在本插件里，它就在本插件的 jar 里，内核的 classpath 上不出现任何
 * 摘要措辞。占位符 {@code {maxSummaryChars}} 由内核替换。
 *
 * @author zcd
 */
public final class CompactPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CompactPlugin.class);

    @Override
    public void start(PluginContext context) {
        // 先装配再注册：处理器一旦注册就可能被调用，依赖必须已经就绪
        PluginConfig config = PluginConfig.from(context.configuration());
        SummaryPrompt prompt = SummaryPrompt.load();
        context.contribute(CompactionStrategyRequest.class,
                new CompactionStrategyContribution(prompt, config));
        LOG.info("压缩插件已启动: keepRecentMessages={} maxSummaryChars={} 指令长度={}",
                config.keepRecentMessages(), config.maxSummaryChars(), prompt.text().length());
    }
}
