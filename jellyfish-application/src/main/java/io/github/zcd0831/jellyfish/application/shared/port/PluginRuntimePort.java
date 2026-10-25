package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;

import java.util.List;

/**
 * 插件的发现与装载（PF4J）。装/启/停/卸都是运行期动作；同 id 升级要求重启（D24-3、D24-2）。
 * 规则出处：模块-应用层 §6、模块-基础设施层 §11。
 */
public interface PluginRuntimePort {

    List<PluginDescriptor> discover();

    /** 插件带来的工具声明：从 jar 内 `META-INF/jellyfish/tools/*.json` 读，不落盘。 */
    List<ToolDefinition> toolsOf(PluginId pluginId);

    void start(PluginId pluginId);

    void stop(PluginId pluginId);

    void unload(PluginId pluginId);

    /** 该插件对某个端口（按端口短名）的实现；没有则返回 null。 */
    Object extension(PluginId pluginId, String capability);

    /** jar 里重复打包的宿主依赖（如 Jackson / OkHttp）：非空 ⇒ 拒绝安装（§7.6 规则 5）。 */
    List<String> conflictsOf(PluginId pluginId);
}
