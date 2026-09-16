package zcd.jellyfish.infra.plugin;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * PF4J 生命周期与 {@link JellyfishPlugin} 之间的适配器。
 * <p>
 * 职责只有一条：把 PF4J 调用的 {@code start()} / {@code stop()} 转给插件实现，并传入框架创建的能力上下文。
 * <p>
 * <b>能力上下文在每次 {@code start()} 时现造</b>：PF4J 的插件实例是<b>装载期创建并长期缓存</b>的
 * （{@code PluginWrapper.getPlugin()}），{@code stop} 不会丢弃它，因此「停止再启动」是本项目里
 * 唯一可用的「重启插件」语义。若上下文在装载期就固化，插件重启后会继续拿着装载时的旧配置段，
 * 配置重载形同虚设；现造之后，{@code stop → start} 这一步就自然完成了「读到新配置」。
 * 代价是每次启动的声明与上下文都是新对象——这正是「注册只允许发生在 start 内」这条契约想要的结果。
 * <p>
 * <b>上下文用工厂而不是对象传入</b>：这样「何时创建」由本类在 {@code start()} 里决定、
 * 「怎么创建」留在插件运行时，两边各自只关心一件事。
 * <p>
 * <b>刻意不做两件事</b>：
 * <ul>
 *     <li><b>不捕获异常</b>：{@code startPlugin(id)} 没有异常处理（实测无异常表），异常必须交给
 *     {@link JellyfishPluginManager} 的安全包装统一转成 {@code FAILED} 并回滚；在适配器里吞掉异常会
 *     让框架以为启动成功，留下半套注册。</li>
 *     <li><b>不设状态、不发通知</b>：状态与通知由 PF4J 与状态监听统一负责，适配器只做委派。</li>
 * </ul>
 *
 * @author zcd
 */
final class JellyfishPluginAdapter extends Plugin {

    /** 插件实现。 */
    private final JellyfishPlugin delegate;

    /** 能力上下文工厂：每次启动时调用一次。 */
    private final Supplier<PluginContext> contextFactory;

    /**
     * 构造适配器。
     *
     * @param wrapper        插件包装器
     * @param delegate       插件实现，不可为 {@code null}
     * @param contextFactory 能力上下文工厂，不可为 {@code null}；每次 {@code start()} 都会调用
     */
    JellyfishPluginAdapter(PluginWrapper wrapper, JellyfishPlugin delegate,
                           Supplier<PluginContext> contextFactory) {
        super(wrapper);
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory must not be null");
    }

    @Override
    public void start() {
        delegate.start(contextFactory.get());
    }

    @Override
    public void stop() {
        delegate.stop();
    }
}
