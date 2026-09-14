package zcd.jellyfish.cli.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.permission.PermissionPolicyProvider;

import javax.inject.Singleton;

/**
 * Agent 模块的 Dagger2 模块。
 * <p>
 * 只需要一个绑定：把 {@link PermissionPolicyProvider} 绑到 {@link AgentManager}。
 * 后者自带 {@code @Inject} 构造器（{@code RuntimeConfig} + {@code AgentRegistry} + {@code EventPublisher}），
 * 依赖由 Dagger 自行装配，不需要在这里重复声明。
 * <p>
 * 之所以绑在 Agent 模块而不是 Permission 模块：策略来源本就来自 agent 定义，谁提供实现谁负责绑定；
 * 且同一类型只能有一个绑定，占位实现（见 {@code PermissionModule} 的历史注释）必须先删掉。
 *
 * @author zcd
 */
@Module
public final class AgentModule {

    private AgentModule() {
    }

    /**
     * 提供权限策略来源。
     * <p>
     * 直接返回 {@link AgentManager} 本身而不是新写一个适配器：它已经是「把 agentId 映射成策略」的那一层，
     * 再包一层只会让「未命中即 fail-open」这条规则出现第二个落点。
     *
     * @param agentManager agent 门面
     * @return 策略来源
     */
    @Provides
    @Singleton
    static PermissionPolicyProvider providePermissionPolicyProvider(AgentManager agentManager) {
        return agentManager;
    }
}
