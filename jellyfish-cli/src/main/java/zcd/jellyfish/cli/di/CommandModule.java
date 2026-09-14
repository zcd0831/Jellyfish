package zcd.jellyfish.cli.di;

import dagger.Module;

/**
 * 命令模块的 Dagger2 模块。
 * <p>
 * <b>本模块没有 {@code @Provides}</b>：{@code CommandManager} 只依赖 {@code ExtensionRegistry}
 * （已由 {@link ExtensionModule} 作为单例提供），自身带 {@code @Inject} 构造器，由 Dagger 自行装配。
 * <p>
 * <b>本模块不注册任何命令处理器</b>：系统命令与插件命令同源，都经 {@code ExtensionRegistry.handle(...)}
 * 落同一份注册表，命令域只负责解析、分发与清单。内核系统命令（{@code /help} / {@code /new} / {@code /session} /
 * {@code /resume} / {@code /model} / {@code /agent} / {@code /mode} / {@code /status} / {@code /usage} / {@code /todo}）
 * 已由 {@code core/command/SystemCommands} 以 owner = {@code "core"} 注册；{@code /exit} 归外壳，
 * {@code /compact} 等仍待落地。
 *
 * @author zcd
 */
@Module
public final class CommandModule {

    private CommandModule() {
    }
}
