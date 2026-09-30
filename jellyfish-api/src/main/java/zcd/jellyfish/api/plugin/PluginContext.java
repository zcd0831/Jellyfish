package zcd.jellyfish.api.plugin;

import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 插件上下文：插件与内核交互的唯一入口。
 * <p>
 * 注册遵循「类型即地址」：内核在某个调用点构造一个请求子类（工具调用、具名命令……）并交给注册表，
 * 插件则按同一个类声明自己能处理它。没有 ID、没有注册表、没有需要事先声明的清单。
 * <p>
 * 调用语义全部由入口和方法本身表达，插件不需要也不应该知道更多：
 * <ul>
 *     <li>{@link #handle}：按路由键精确匹配，同一「请求类型 + 路由键」至多一个处理器，
 *     重复注册按 {@link RegisterOptions} 的覆盖声明处理；</li>
 *     <li>{@link #contribute}：类型级贡献，允许 0..N 个处理器，查找时按 {@code order} 升序返回。</li>
 * </ul>
 * 唯一性、空表行为、执行位置与失败语义都由内核在调用点决定，不向插件暴露成参数。
 * <p>
 * <b>方向不止一条</b>：{@link #handle} / {@link #contribute} / {@link #observe} 是「内核回头找插件」，
 * {@link #emit} 与 {@link #submit} 是「插件往外发」。四条边都不是同步回调：
 * 前三条由内核在定义好的调用点同步或异步派发，后两条只写队列（通知队列 / 动作队列）。
 * 插件因此<b>无法在同一次调用里影响内核的状态机</b>，而反过来内核也不会在动作完成时回头调插件——
 * 结果由插件轮询 {@link ActionHandle} 取得。
 * <p>
 * <b>描述符随处理器一起落表</b>：工具这类需要向内核暴露元信息的扩展点，把
 * {@code ToolDescriptor} 作为 {@code descriptor} 传入，内核在需要时按类型取回，
 * 因此不存在第二份「工具清单」需要插件额外维护。
 * <p>
 * <b>注册与注销</b>：存活期内的任意时刻都可调 {@link #handle} / {@link #contribute} /
 * {@link #observe} / {@link #emit}（<b>不限于 </b>{@code start()} 之内），{@link #stop()} 之后则一律失败。
 * 需要主动解除某条注册时，用注册时拿到的 {@link Subscription#close()}；
 * 插件停止时框架仍会按 {@code pluginId} 一次性把剩下的收干净，因此注销是可选优化而不是必须动作。
 * <p>
 * <b>投递与注册共用同一条存活边界</b>：{@link #submit} 同样在 {@code stop()} 之后当场失败，
 * 并且停止时会把该插件尚未排空的动作整批丢弃（见 {@link ActionHandle}）。
 *
 * @author zcd
 */
public interface PluginContext {

    /**
     * 获取插件标识。
     * <p>
     * 用于日志前缀与工具名命名空间；插件身份来自描述符，插件无法自行指定。
     *
     * @return 插件标识
     */
    String pluginId();

    /**
     * 派生一个子上下文：身份是本插件标识加 {@link PluginOwnerNamespace#SEPARATOR} 加子标识，
     * 能力与配置与本上下文完全一致。
     * <p>
     * <b>解决什么问题</b>：一个插件常常由多个彼此独立的子单元组成（脚本插件的每个脚本、多后端插件的
     * 每个后端……）。若它们的注册全部挂在同一个插件标识下，诊断输出里就分不出「这个工具到底是谁提供的」，
     * 也无法按子单元治理。派生一个子上下文，注册就会落在自己的命名空间下，而框架卸载时仍按
     * 插件标识一次性把它们全收干净——两者不是二选一，而是靠命名空间同时成立。
     * <p>
     * <b>为何不能越界</b>：子身份恒从<b>当前</b>身份派生，插件无法借此注册到别人的命名空间里；
     * 也没有任何入口能让它指定一个与自己的插件标识无关的 owner。
     * <p>
     * <b>子标识的取值规则</b>见 {@link PluginOwnerNamespace#requireChildId(String)}；
     * 不满足就当场报错（属于编程错误，不是运行时条件）。
     * <p>
     * <b>可以继续派生</b>（形状为 {@code a::b::c}）：层级回收天然支持，因此不特意禁止，
     * 但一层通常就够——每多一层，诊断输出就多一份推导成本。
     * <p>
     * <b>何时调、调几次都不限</b>：它只是个轻量对象，不产生任何注册，因此不做存活检查；
     * 真正会被拦住的是通过它注册的那一刻——若宿主上下文已失效，注册会当场报错。
     *
     * @param childId 子标识，不可为空白，且不得含空白字符、路径分隔符与命名空间分隔符
     * @return 子上下文，保证非 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 子标识不合法时抛出
     */
    PluginContext subContext(String childId);

    /**
     * 获取本插件在 {@code jellyfish.json} 中的配置段。
     * <p>
     * 双源合并与环境变量替换已由内核完成，插件拿到的是最终值；插件不允许自行读配置文件。
     *
     * @return 不可变配置映射，未配置时为空映射而非 {@code null}
     */
    Map<String, Object> configuration();

    /**
     * 获取运行时信息只读快照：本进程跑在哪种外壳里、有没有可交互界面、能不能弹审批。
     * <p>
     * <b>用途是优雅降级</b>：只在某一种外壳里有意义的贡献，插件应当据此前置判断，而不是先注册再指望
     * 「没人看得见也不出问题」。{@link RuntimeInfo#supportsApproval()} <b>不表示</b>此刻有审批者在线
     * （见该类型的注释），因此它只能用于「要不要提供这个能力」，不能用于安全判定。
     * <p>
     * <b>它不开放会话与工作目录</b>：四个字段都是进程级事实，插件仍然拿不到
     * {@code sessionId}、{@code agentId}、{@code cwd} 与请求内容。
     *
     * @return 快照，保证非 {@code null}；外壳未写入时是 {@link RuntimeInfo#unknown()}
     */
    RuntimeInfo runtimeInfo();

    /**
     * 注册唯一处理器：同一「请求类型 + 路由键」至多一个。
     * <p>
     * 适合工具、具名命令这类「一个名字对应一个实现」的请求：重复注册默认失败，
     * 需要替换既有实现时显式声明 {@link RegisterOptions#override(boolean)}。
     *
     * @param requestType 请求类型，即内核在调用点构造的那个类，不可为 {@code null}
     * @param routeKey    路由键，通常取自请求自身携带的名字，不可为 {@code null}
     * @param descriptor  处理器描述符（如 {@code ToolDescriptor}），可为 {@code null}
     * @param handler     处理器，不可为 {@code null}
     * @param options     注册选项
     * @param <C>         请求类型
     * @param <R>         结果类型
     * @return 注册句柄，插件卸载时可用于提前解除
     * @throws zcd.jellyfish.api.JellyfishException 插件上下文已失效（已停止）时抛出
     */
    <C extends ExtensionRequest<R>, R> Subscription handle(Class<C> requestType, String routeKey, Object descriptor,
                                                           ExtensionHandler<C, R> handler, RegisterOptions options);

    /**
     * 注册不带描述符、但声明选项的唯一处理器。
     *
     * @param requestType 请求类型，不可为 {@code null}
     * @param routeKey    路由键，不可为 {@code null}
     * @param handler     处理器，不可为 {@code null}
     * @param options     注册选项
     * @param <C>         请求类型
     * @param <R>         结果类型
     * @return 注册句柄
     */
    default <C extends ExtensionRequest<R>, R> Subscription handle(Class<C> requestType, String routeKey,
                                                                   ExtensionHandler<C, R> handler,
                                                                   RegisterOptions options) {
        return handle(requestType, routeKey, null, handler, options);
    }

    /**
     * 以默认选项注册唯一处理器。
     *
     * @param requestType 请求类型，不可为 {@code null}
     * @param routeKey    路由键，不可为 {@code null}
     * @param descriptor  处理器描述符，可为 {@code null}
     * @param handler     处理器，不可为 {@code null}
     * @param <C>         请求类型
     * @param <R>         结果类型
     * @return 注册句柄
     */
    default <C extends ExtensionRequest<R>, R> Subscription handle(Class<C> requestType, String routeKey,
                                                                   Object descriptor,
                                                                   ExtensionHandler<C, R> handler) {
        return handle(requestType, routeKey, descriptor, handler, RegisterOptions.DEFAULT);
    }

    /**
     * 注册不带描述符的唯一处理器。
     *
     * @param requestType 请求类型，不可为 {@code null}
     * @param routeKey    路由键，不可为 {@code null}
     * @param handler     处理器，不可为 {@code null}
     * @param <C>         请求类型
     * @param <R>         结果类型
     * @return 注册句柄
     */
    default <C extends ExtensionRequest<R>, R> Subscription handle(Class<C> requestType, String routeKey,
                                                                   ExtensionHandler<C, R> handler) {
        return handle(requestType, routeKey, null, handler, RegisterOptions.DEFAULT);
    }

    /**
     * 注册类型级贡献：同一请求类型允许多个处理器，查找时按 {@code order} 升序返回。
     * <p>
     * 适合「收集多个片段」的请求。调用顺序、短路与结果合并都由内核调用点自行驱动，
     * 需要聚合多个结果的调用点让请求自带结果容器承接。
     *
     * @param requestType 请求类型，不可为 {@code null}
     * @param descriptor  处理器描述符，可为 {@code null}
     * @param handler     处理器，不可为 {@code null}
     * @param options     注册选项，{@code order} 在此声明
     * @param <C>         请求类型
     * @param <R>         结果类型
     * @return 注册句柄，插件卸载时可用于提前解除
     * @throws zcd.jellyfish.api.JellyfishException 插件上下文已失效（已停止）时抛出
     */
    <C extends ExtensionRequest<R>, R> Subscription contribute(Class<C> requestType, Object descriptor,
                                                               ExtensionHandler<C, R> handler,
                                                               RegisterOptions options);

    /**
     * 以默认选项注册带描述符的类型级贡献。
     *
     * @param requestType 请求类型，不可为 {@code null}
     * @param descriptor  处理器描述符，可为 {@code null}
     * @param handler     处理器，不可为 {@code null}
     * @param <C>         请求类型
     * @param <R>         结果类型
     * @return 注册句柄
     */
    default <C extends ExtensionRequest<R>, R> Subscription contribute(Class<C> requestType, Object descriptor,
                                                                       ExtensionHandler<C, R> handler) {
        return contribute(requestType, descriptor, handler, RegisterOptions.DEFAULT);
    }

    /**
     * 注册不带描述符、但声明顺序的类型级贡献。
     *
     * @param requestType 请求类型，不可为 {@code null}
     * @param handler     处理器，不可为 {@code null}
     * @param options     注册选项，{@code order} 在此声明
     * @param <C>         请求类型
     * @param <R>         结果类型
     * @return 注册句柄
     */
    default <C extends ExtensionRequest<R>, R> Subscription contribute(Class<C> requestType,
                                                                       ExtensionHandler<C, R> handler,
                                                                       RegisterOptions options) {
        return contribute(requestType, null, handler, options);
    }

    /**
     * 以默认选项注册类型级贡献。
     *
     * @param requestType 请求类型，不可为 {@code null}
     * @param handler     处理器，不可为 {@code null}
     * @param <C>         请求类型
     * @param <R>         结果类型
     * @return 注册句柄
     */
    default <C extends ExtensionRequest<R>, R> Subscription contribute(Class<C> requestType,
                                                                       ExtensionHandler<C, R> handler) {
        return contribute(requestType, null, handler, RegisterOptions.DEFAULT);
    }

    /**
     * 投递一条主动动作给内核。
     * <p>
     * <b>入队即返回，绝不在本方法的调用栈上执行</b>：内核把动作排进待排空队列，
     * 在定义好的安全点（回合内的一轮之后 / 回合收敛点 / 回合边界）才执行它。
     * 因此在处理器里调用本方法不会造成重入——「handler 还没返回，动作已经改了会话」这种情形不存在。
     * <p>
     * <b>为什么会有失败</b>：动作只能投进<b>正在跑的那个回合</b>（见 {@link zcd.jellyfish.api.action.DeliverAs}），
     * 而这个内核里回合的边界由外壳决定。因此插件从事件订阅回调（异步投递，可能落在回合刚结束之后）
     * 或自己的线程上投递时，拿不到在途回合是<b>正常结果</b>，回报
     * {@link zcd.jellyfish.api.action.ActionStatus#FAILED} 而不是抛异常——异常会把一条「这次没赶上」
     * 变成需要 try/catch 的错误路径。
     * <p>
     * <b>能投什么是有界的</b>：只能投 {@link PluginAction} 上列出的那几种动作，
     * 没有「直接改内核状态」「直接执行工具」「关闭会话」这些入口。动作放宽的是「谁能发起」，
     * 不是「发起之后能做什么」——{@code sendUserMessage} 引起的工具调用照旧过完整的权限与审批链。
     * <p>
     * <b>停止之后一律失败</b>：与注册同一条存活边界，{@code stop()} 之后本方法当场抛
     * {@link zcd.jellyfish.api.JellyfishException}（fail-closed），而不是静默落进一个没人排空的队列。
     *
     * @param action 动作，不可为 {@code null}
     * @return 动作句柄，保证非 {@code null}；结果靠轮询取得
     * @throws zcd.jellyfish.api.JellyfishException 插件上下文已失效（已停止）时抛出
     * @see PluginAction
     * @see ActionHandle
     */
    ActionHandle submit(PluginAction action);

    /**
     * 订阅内核通知。
     *
     * @param eventType 通知类型，不可为 {@code null}
     * @param filter    过滤谓词，{@code null} 表示接收全部
     * @param listener  监听器，不可为 {@code null}
     * @param <E>       通知类型
     * @return 订阅句柄，插件卸载时可用于提前解除
     * @throws zcd.jellyfish.api.JellyfishException 插件上下文已失效（已停止）时抛出
     */
    <E extends JellyfishEvent> Subscription observe(Class<E> eventType, Predicate<E> filter, Consumer<E> listener);

    /**
     * 订阅内核通知，接收该类型的全部通知。
     *
     * @param eventType 通知类型，不可为 {@code null}
     * @param listener  监听器，不可为 {@code null}
     * @param <E>       通知类型
     * @return 订阅句柄
     */
    default <E extends JellyfishEvent> Subscription observe(Class<E> eventType, Consumer<E> listener) {
        return observe(eventType, null, listener);
    }

    /**
     * 发布通知。
     * <p>
     * 方向与注册相反：注册是内核回头找插件，发布是插件单向观察内核；发布不产生返回值，失败只记账。
     *
     * @param event 通知事件，不可为 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 插件上下文已失效（已停止）时抛出
     */
    void emit(JellyfishEvent event);
}
