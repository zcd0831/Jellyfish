package zcd.jellyfish.api.plugin;

import zcd.jellyfish.api.JellyfishException;

/**
 * 插件生命周期契约：一个插件 jar 一个实现类，由 {@code plugin.properties} 的 {@code plugin.class} 指定。
 * <p>
 * 实现类必须提供<b>公开无参构造器</b>：插件由 PF4J 的插件类加载器实例化，无法走 Dagger 装配。
 * <p>
 * <b>注册窗口是插件的整个存活期</b>：框架在调用 {@link #start(PluginContext)} 前创建
 * {@code owner = pluginId} 的能力上下文，此后到 {@link #stop()} 为止，插件可以在<b>任意时刻</b>
 * 注册、订阅与发布——运行期才发现自己能提供哪些能力的插件（例如 MCP 客户端在握手后才知道工具集）
 * 必须如此，这也是它与「只能堆在启动期」的根本差别。回收仍然只按 owner 一次收干净，
 * 因此运行期注册不会留下没人收的登记。
 * <p>
 * <b>{@link #stop()} 之后注册一律失败</b>：框架回收时会把上下文置为失效，此后任何注册、订阅、
 * 发布都当场报错（fail-closed），而不是静默落表变成「插件已停、工具还能调」的幽灵注册。
 * 因此插件若有后台线程会注册，必须在 {@code stop()} 里先停下它们；
 * 需要主动注销已注册的能力时用 {@code Subscription.close()}。
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
     * <p>
     * <b>它不是唯一的注册时机</b>：存活期内的任意时刻都可以继续注册（见类注释），
     * 本方法只是「有什么能力是启动期就能确定的、就先挂上」的那个常用落点。
     *
     * @param context 能力上下文，由框架创建，不可为 {@code null}
     * @throws JellyfishException 启动失败时抛出
     */
    void start(PluginContext context);

    /**
     * 停止插件：释放自己申请的资源（连接、线程、子进程）。
     * <p>
     * 处理器注册与事件订阅由框架按 owner 回收，插件无需为了回收而自行反注册；
     * 但框架不管插件自己的资源，因此持有资源的插件必须覆写本方法。
     * <p>
     * <b>本方法返回（或抛错）之后，上下文即失效</b>：此时仍在跑的注册路径会当场报错。
     * 所以「先停下会产生注册的线程与定时器，再返回」是插件这边的责任——把这条留到
     * 下一个插件去做，它没有这个义务，后果是噪声日志，而噪声会把人引向错误的方向。
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
