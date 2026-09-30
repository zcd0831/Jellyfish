package zcd.jellyfish.infra.plugin;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 插件上下文实现：把 {@code pluginId} 绑定为全部注册的 owner。
 * <p>
 * {@code owner} 贯穿注册、覆盖记录、卸载回收、诊断快照四件事，是插件系统能管住资源的那根线；
 * 插件侧只看到一个 {@link PluginContext}，不感知注册表与派发策略，也不需要自行反注册。
 * <p>
 * 「注册边界」在这里不靠清单，而靠本类暴露的入口本身：插件只能拿到 {@link PluginContext} 的几个方法，
 * 能注册什么完全取决于它拿得到哪些请求类型。核心内部使用的请求类型不外露，插件也就无从注册。
 * <p>
 * <b>为何要提供子上下文</b>：插件常由多个彼此独立的子单元组成（脚本插件的每个脚本、多后端插件的每个
 * 后端……），若它们的注册全挂在同一个插件标识下，诊断输出就分不出「这个工具是谁提供的」，
 * 也无法按子单元治理。{@link #subContext(String)} 把子单元放到自己的命名空间下，
 * 而 {@code PluginContextFactory.release} 按命名空间回收——「可归因」与「一次收干净」因此同时成立。
 * <p>
 * <b>子身份恒从当前身份派生</b>，插件无法构造出一个与自己无关的 owner，因此这条能力不会变成越权入口。
 * <p>
 * <b>注册窗口是「插件存活期」而不是「{@code start()} 之内」</b>：插件可以在启动之后的任意时刻
 * 继续注册（运行期才发现能力集的插件必须如此），框架回收仍按 owner 命名空间一次收干净。
 * 窗口放宽的代价是「插件停止之后仍然注册」成了一类真实存在的竞态，因此本类持有一个与全部子上下文
 * 共享的 {@link ContextLifecycle}：停止时置为关闭，此后一切注册、订阅与发布都<b>当场失败</b>
 * （fail-closed），而不是落进注册表变成「插件已停、工具还能调」的幽灵注册。
 * <p>
 * <b>为什么在 {@code infra/plugin} 而不是策略包里</b>：本类是插件侧的能力上下文，与插件加载同属插件运行时；
 * 它只调用 {@link ExtensionRegistry} 与 {@link EventChannel} 的公开方法，不依赖任何包级私有细节，
 * 因此装配入口（{@link PluginContextFactory}）可以外置而不产生包级循环。
 *
 * @author zcd
 */
public final class PluginContextImpl implements PluginContext {

    /** 插件声明，提供身份与配置段。 */
    private final PluginDeclaration declaration;

    /** 同步扩展点策略（同一份 TypeRegistry 的同步视图）。 */
    private final ExtensionRegistry extensions;

    /** 事件通道：插件的订阅与发布都落在这里。 */
    private final EventChannel events;

    /** 存活标记：与全部子上下文共享，关闭后拒绝一切注册、订阅与发布。 */
    private final ContextLifecycle lifecycle;

    /** 运行时信息持有者：外壳在启动期写入，这里只读快照。 */
    private final RuntimeInfoHolder runtimeInfo;

    /** 动作队列：插件主动动作的入站队列，同一个根插件下的子上下文共用。 */
    private final ActionQueue actions;

    /** 会话域服务：仅用于会话扩展条目的读写。 */
    private final SessionManager sessions;

    /**
     * 构造一个<b>自持有存活标记</b>的插件上下文。
     * <p>
     * 不经过 {@link PluginContextFactory} 的装配（例如单元测试）用它：标记由本上下文独占，
     * 没有任何入口能让它失效。生产装配一律走工厂的
     * {@link PluginContextFactory#create(PluginDeclaration)}，那条路径才会登记标记以便停止时关闭。
     * <p>
     * 运行时信息用缺省的「未知外壳」：这条路得不到外壳启动流程写入的值，
     * 而给一个保守的缺省比让调用方被迫伪造一个外壳要好。动作队列同理，建一个独立实例——
     * 它与该插件自己的两个参数无关，且没有回收集道。
     *
     * @param declaration 插件声明，不可为 {@code null}
     * @param extensions  同步扩展点策略，不可为 {@code null}
     * @param events      事件通道，不可为 {@code null}
     */
    public PluginContextImpl(PluginDeclaration declaration, ExtensionRegistry extensions, EventChannel events,
                             SessionManager sessions) {
        this(declaration, extensions, events, new ContextLifecycle(), new RuntimeInfoHolder(), new ActionQueue(),
                sessions);
    }

    /**
     * 构造插件上下文，共用调用方给定的存活标记、运行时信息持有者与动作队列。
     *
     * @param declaration 插件声明，不可为 {@code null}
     * @param extensions  同步扩展点策略，不可为 {@code null}
     * @param events      事件通道，不可为 {@code null}
     * @param lifecycle   存活标记，不可为 {@code null}；子上下文必须复用父上下文的同一个实例
     * @param runtimeInfo 运行时信息持有者，不可为 {@code null}；子上下文同样复用它
     * @param actions     动作队列，不可为 {@code null}；子上下文同样复用它
     * @param sessions    会话域服务，不可为 {@code null}；子上下文同样复用它
     */
    PluginContextImpl(PluginDeclaration declaration, ExtensionRegistry extensions, EventChannel events,
                      ContextLifecycle lifecycle, RuntimeInfoHolder runtimeInfo, ActionQueue actions,
                      SessionManager sessions) {
        this.declaration = declaration;
        this.extensions = extensions;
        this.events = events;
        this.lifecycle = lifecycle;
        this.runtimeInfo = runtimeInfo;
        this.actions = actions;
        this.sessions = sessions;
    }

    @Override
    public String pluginId() {
        return declaration.getPluginId();
    }

    @Override
    public Map<String, Object> configuration() {
        return declaration.getConfiguration();
    }

    @Override
    public RuntimeInfo runtimeInfo() {
        return runtimeInfo.snapshot();
    }

    @Override
    public PluginContext subContext(String childId) {
        // 身份从「当前」身份派生而非从根插件标识派生：子上下文再派生子上下文就会自然形成
        // a::b::c 这样的层级，而回收侧的前缀匹配本就支持任意深度，无需特殊处理
        String childPluginId = pluginId() + PluginOwnerNamespace.SEPARATOR
                + PluginOwnerNamespace.requireChildId(childId);
        // 子上下文复用父上下文的存活标记：否则回收根上下文管不住子上下文，幽灵注册会从这条缝回来。
        // 本方法刻意不做存活检查——它不产生任何注册，真正需要被拦住的是注册那一刻。
        // 运行时信息持有者也一并复用：外壳是进程级事实，子单元与父单元看到的必须一致
        return new PluginContextImpl(PluginDeclaration.of(childPluginId, declaration.getConfiguration()),
                extensions, events, lifecycle, runtimeInfo, actions, sessions);
    }

    @Override
    public <C extends ExtensionRequest<R>, R> Subscription handle(Class<C> requestType, String routeKey,
                                                                 Object descriptor, ExtensionHandler<C, R> handler,
                                                                 RegisterOptions options) {
        requireAlive("register handler");
        return extensions.handle(pluginId(), requestType, routeKey, descriptor, handler, options);
    }

    @Override
    public <C extends ExtensionRequest<R>, R> Subscription contribute(Class<C> requestType, Object descriptor,
                                                                     ExtensionHandler<C, R> handler,
                                                                     RegisterOptions options) {
        requireAlive("register contribution");
        return extensions.contribute(pluginId(), requestType, descriptor, handler, options);
    }

    @Override
    public <E extends JellyfishEvent> Subscription observe(Class<E> eventType, Predicate<E> filter,
                                                           Consumer<E> listener) {
        requireAlive("subscribe event");
        return events.subscribe(pluginId(), eventType, filter, listener);
    }

    @Override
    public void emit(JellyfishEvent event) {
        requireAlive("publish event");
        events.publish(event);
    }

    @Override
    public ActionHandle submit(PluginAction action) {
        requireAlive("submit action");
        return actions.submit(pluginId(), action);
    }

    @Override
    public void putExtensionEntry(String sessionId, String key, Map<String, Object> value) {
        requireAlive("write session extension entry");
        sessions.putExtensionEntry(sessionId, pluginId(), namespaced(key), value);
    }

    @Override
    public void removeExtensionEntry(String sessionId, String key) {
        requireAlive("remove session extension entry");
        sessions.removeExtensionEntry(sessionId, namespaced(key));
    }

    @Override
    public List<SessionExtensionEntry> extensionEntries(String sessionId) {
        requireAlive("read session extension entries");
        return sessions.extensionEntriesOf(sessionId, pluginId());
    }

    /**
     * 把插件给的 key 拼上本上下文的全限定 owner 前缀。
     * <p>
     * <b>前缀恒从当前身份派生</b>，插件没有任何入口能写别人的命名空间。
     * <p>
     * <b>key 里不得含命名空间分隔符</b>：否则 {@code plugin-a::a::b} 读不出来它到底是「子上下文 a 写的
     * key b」还是「根上下文写的 key a::b」，诊断输出就失去了可归因性。需要层级就用别的字符。
     *
     * @param key 插件给的不含前缀的 key
     * @return 带前缀的完整 key
     * @throws JellyfishException key 为空白或含命名空间分隔符时抛出
     */
    private String namespaced(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new JellyfishException("session extension entry key must not be blank: pluginId="
                    + pluginId());
        }
        if (key.contains(PluginOwnerNamespace.SEPARATOR)) {
            throw new JellyfishException("会话扩展条目的 key 不得含 \"" + PluginOwnerNamespace.SEPARATOR
                    + "\": pluginId=" + pluginId());
        }
        return pluginId() + PluginOwnerNamespace.SEPARATOR + key;
    }

    /**
     * 注册 / 订阅 / 发布前的存活检查。
     * <p>
     * <b>为什么是抛异常而不是静默忽略</b>：静默忽略会让调用方以为注册成功，而失败的表现是
     * 「工具怎么没了」——排查成本远高于当场报错。抛错还把「插件代码里有晚于 {@code stop()} 的
     * 注册路径」这件事在第一次出现时就暴露出来。
     *
     * @param operation 正在尝试的操作，用于错误信息
     * @throws JellyfishException 上下文已关闭时抛出
     */
    private void requireAlive(String operation) {
        if (lifecycle.isClosed()) {
            throw new JellyfishException("plugin context already closed, cannot " + operation
                    + ": pluginId=" + pluginId());
        }
    }
}
