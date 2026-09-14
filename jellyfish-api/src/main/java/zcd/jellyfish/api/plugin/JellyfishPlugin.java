package zcd.jellyfish.api.plugin;

import zcd.jellyfish.api.JellyfishException;

/**
 * 插件生命周期契约：一个插件 jar 一个实现类，由 {@code plugin.properties} 的 {@code plugin.class} 指定。
 * <p>
 * 实现类必须提供<b>公开无参构造器</b>：插件由 PF4J 的插件类加载器实例化，无法走 Dagger 装配。
 * <p>
 * <b>注册窗口只在本接口的 {@link #start(PluginContext)} 内</b>：框架在调用前创建
 * {@code owner = pluginId} 的能力上下文，卸载时按 owner 批量回收；在 start 之外注册的处理器
 * 不在回收范围内，属于未定义行为。
 * <p>
 * 插件只能注册回调处理器、订阅与发布事件，<b>不能发起回调</b>，能力边界与跨语言脚本插件完全一致。
 *
 * @author zcd
 */
public interface JellyfishPlugin {

    /**
     * 启动插件：注册处理器、订阅事件、申请资源。
     * <p>
     * 本方法抛错时插件转 {@code FAILED}：框架会回收本次已完成的注册、发布状态变更通知，
     * 并继续处理其它插件，因此不必自行回滚。
     *
     * @param context 能力上下文，由框架创建，不可为 {@code null}
     * @throws JellyfishException 启动失败时抛出
     */
    void start(PluginContext context);

    /**
     * 停止插件：释放自己申请的资源（连接、线程、子进程）。
     * <p>
     * 处理器注册与事件订阅由框架按 owner 回收，插件无需、也不应自行反注册；
     * 但框架不管插件自己的资源，因此持有资源的插件必须覆写本方法。
     * <p>
     * 注意：{@code start} 抛错时框架<b>不保证</b>会调用本方法（PF4J 对未进入 STARTED 的插件会跳过停止），
     * 因此 {@code start} 内部自己申请到一半的资源要在 catch 里自行释放。
     * <p>
     * 本方法抛错只被记录，不影响其它插件的停止与内核关闭。
     *
     * @throws JellyfishException 停止失败时抛出
     */
    default void stop() {
    }
}
