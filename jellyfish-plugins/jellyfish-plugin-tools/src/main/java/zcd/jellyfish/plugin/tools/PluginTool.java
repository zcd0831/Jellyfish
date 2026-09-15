package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 插件内单个工具的契约：把「工具名片 + 处理器」绑成一个可注册对象。
 * <p>
 * 存在的理由与内核的设计同源：{@code PluginContext.handle} 要求描述符与处理器<b>一起</b>落表，
 * 两者本就是同一件事的两面。把这条绑定收进一个接口，插件侧就只需遍历工具清单，
 * 不必在每次注册时重复「名字写两遍」——名片里的名字即路由键，避免两处不一致。
 * <p>
 * 实现类必须无状态或自行保证线程安全：工具在 ReAct 执行线程上被内联调用，
 * 同一个实例会被反复使用。
 *
 * @author zcd
 */
public interface PluginTool extends ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /**
     * 获取工具名片。
     *
     * @return 工具描述符，不可为 {@code null}
     */
    ToolDescriptor descriptor();

    /**
     * 获取工具名，即注册与调用的路由键。
     *
     * @return 工具名
     */
    default String name() {
        return descriptor().getName();
    }

    /**
     * 把自己注册进插件上下文。
     *
     * @param context 插件上下文，不可为 {@code null}
     * @return 注册句柄，插件卸载时由框架统一回收，通常无需自行持有
     */
    default Subscription register(PluginContext context) {
        return context.handle(ToolCallRequest.class, name(), descriptor(), this);
    }
}
