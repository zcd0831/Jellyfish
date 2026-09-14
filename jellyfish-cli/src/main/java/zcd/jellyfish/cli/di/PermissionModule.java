package zcd.jellyfish.cli.di;

import dagger.Module;

/**
 * 权限模块的 Dagger2 模块。
 * <p>
 * <b>本模块没有 {@code @Provides}</b>：判定所需的协作者（{@code PermissionManager}、
 * {@code ReadOnlyTools}）都带 {@code @Inject} 构造器，由 Dagger 自行装配。
 * <p>
 * 接口 {@code PermissionPolicyProvider} 的绑定<b>不在这里</b>：它由 {@link AgentModule} 绑到
 * {@code AgentManager}（依赖方向是 agent → permission，与既有约定一致）。这里曾经放过一个
 * 「一律返回 unrestricted」的占位实现，AgentManager 落地后已删除——保留它会与 AgentModule 形成
 * 同类型双绑定，Dagger 编译期即失败。
 * <p>
 * 本模块也不注册任何权限拦截处理器：拦截是插件的扩展点（类型级贡献），内核只做调用点。
 *
 * @author zcd
 */
@Module
public final class PermissionModule {

    private PermissionModule() {
    }
}
