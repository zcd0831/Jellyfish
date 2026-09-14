package zcd.jellyfish.cli.di;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.permission.PermissionPolicy;
import zcd.jellyfish.infra.permission.PermissionPolicyProvider;
import zcd.jellyfish.infra.permission.ReadOnlyTools;

import javax.inject.Singleton;

/**
 * 权限模块的 Dagger2 模块。
 * <p>
 * 只提供一个绑定：{@link PermissionPolicyProvider} 是接口，Dagger 无法自行构造。其余协作者
 * （{@link PermissionManager}、{@link ReadOnlyTools}）都带 {@code @Inject} 构造器，由 Dagger 自行装配，
 * 不需要在此重复声明。
 * <p>
 * 本模块刻意不注册任何权限拦截处理器：拦截是插件的扩展点（类型级贡献），内核只做调用点。
 *
 * @author zcd
 */
@Module
public final class PermissionModule {

    private PermissionModule() {
    }

    /**
     * 提供权限策略来源。
     * <p>
     * 当前是占位实现：一律返回「无策略」，判定按 fail-open 放行。本轮只交付 Permission 模块本身，
     * agent 定义的装载（{@code AgentManager}）尚未落地。
     *
     * @return 策略来源
     */
    @Provides
    @Singleton
    static PermissionPolicyProvider providePermissionPolicyProvider() {
        // TODO AgentManager 落地后，本方法改为传入按 agents.json 装载的 agent 定义（含权限策略），
        //      由它实现 PermissionPolicyProvider：返回不可变快照，保证一次判定读到的策略是同一份。
        return agentId -> PermissionPolicy.unrestricted();
    }
}
