package zcd.jellyfish.cli.di;

import dagger.Module;

/**
 * 命令模块的 Dagger2 模块。
 * <p>
 * <b>本模块没有 {@code @Provides}</b>：{@code CommandManager} 只依赖 {@code ExtensionRegistry}
 * （已由 {@link ExtensionModule} 作为单例提供），自身带 {@code @Inject} 构造器，由 Dagger 自行装配。
 * <p>
 * <b>本模块不注册任何命令处理器</b>：系统命令与插件命令同源，都经 {@code ExtensionRegistry.handle(...)}
 * 落同一份注册表，命令域只负责解析、分发与清单。系统命令（{@code /help} / {@code /model} / {@code /agent} /
 * {@code /mode} / {@code /new} / {@code /exit}）尚未落地，续做时由 {@code core} 侧持有 session / model /
 * agent 域服务的组件注册，owner 取 {@code "core"} 以区别于插件。
 *
 * @author zcd
 */
@Module
public final class CommandModule {

    private CommandModule() {
    }
}
