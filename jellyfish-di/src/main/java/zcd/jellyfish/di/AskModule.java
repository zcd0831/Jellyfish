package zcd.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.api.ask.AskPort;
import zcd.jellyfish.infra.ask.AskChannel;

import javax.inject.Singleton;

/**
 * 向用户提问的 Dagger2 模块。
 * <p>
 * <b>为什么需要它</b>：{@link AskChannel} 带 {@code @Inject} 构造器，Dagger 能自行装配具体类型；
 * 但插件拿到的是 api 侧的接口 {@link AskPort}（经 {@code PluginContext.askUser()}），
 * 接口到实现的绑定必须显式声明。这与 {@code PluginModule} 里 {@code SubAgentPort} 的处理同源——
 * 两者都是「内核给插件的出向边」，都只能由装配方把接口接到实现上。
 * <p>
 * <b>绑定在这里而不是 {@link PermissionModule}</b>：审批与提问确实是两类事——审批是权限判定卡在
 * 半路等人放行（等不到按拒绝处理），提问是模型向你确认信息（等不到就照自己的判断继续）。
 * 它们的失败口径刻意不同，因此也各有自己的模块，不共用一条「人工交互」的模糊边界。
 *
 * @author zcd
 */
@Module
public final class AskModule {

    private AskModule() {
    }

    /**
     * 提供面向插件的提问端口。
     * <p>
     * <b>唯一实现是通道本身</b>：它不是一层适配器，而是把 {@link AskChannel} 的公开面收窄成
     * 插件该看到的那一个方法。插件因此拿不到 {@code attach} / {@code resolve}——
     * 答复者只能是外壳，这条边界由类型系统而非约定守住。
     *
     * @param channel 提问通道
     * @return 端口
     */
    @Provides
    @Singleton
    static AskPort provideAskPort(AskChannel channel) {
        return channel;
    }
}
