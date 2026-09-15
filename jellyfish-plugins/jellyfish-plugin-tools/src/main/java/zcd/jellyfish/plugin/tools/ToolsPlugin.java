package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 官方工具插件：注册文件读写、目录列举与文本搜索这几个基础工具。
 * <p>
 * <b>插件类只做一件事——把工具清单交给上下文</b>：注册窗口只在 {@code start} 内，
 * 之后框架按 {@code pluginId} 批量回收，因此这里不需要持有注册句柄，也不实现 {@code stop}。
 * <p>
 * <b>为什么工具不做成内核自带</b>：插件侧只能注册回调，工具的实现与内核生命周期无关；
 * 放在插件里还顺带获得两样东西——{@code jellyfish.json} 的
 * {@code plugins.configurations.jellyfish-tools.readOnlyTools} 可以直接声明哪些工具是只读的
 * （PLAN 模式的白名单来源），以及热部署能力。
 * <p>
 * 工具实例全部无状态，因此插件可以安全地与其它插件并发调用。
 *
 * @author zcd
 */
public final class ToolsPlugin implements JellyfishPlugin {

    /** 本插件提供的全部工具，顺序即注册顺序，无副作用。 */
    private static final List<PluginTool> TOOLS = Collections.unmodifiableList(Arrays.<PluginTool>asList(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool(),
            new ListDirTool(),
            new GrepFilesTool()));

    @Override
    public void start(PluginContext context) {
        for (PluginTool tool : TOOLS) {
            // 名字即路由键，工具名全局唯一这一点由 handle 的同键唯一语义保证
            tool.register(context);
        }
    }
}
