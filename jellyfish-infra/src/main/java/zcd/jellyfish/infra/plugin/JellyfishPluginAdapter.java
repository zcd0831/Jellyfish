package zcd.jellyfish.infra.plugin;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Objects;

/**
 * PF4J 生命周期与 {@link JellyfishPlugin} 之间的适配器。
 * <p>
 * 职责只有一条：把 PF4J 调用的 {@code start()} / {@code stop()} 转给插件实现，并传入框架创建的能力上下文。
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

    /** 能力上下文，由框架按插件声明创建。 */
    private final PluginContext context;

    /**
     * 构造适配器。
     *
     * @param wrapper  插件包装器
     * @param delegate 插件实现，不可为 {@code null}
     * @param context  能力上下文，不可为 {@code null}
     */
    JellyfishPluginAdapter(PluginWrapper wrapper, JellyfishPlugin delegate, PluginContext context) {
        super(wrapper);
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    @Override
    public void start() {
        delegate.start(context);
    }

    @Override
    public void stop() {
        delegate.stop();
    }
}
