package zcd.jellyfish.api.plugin;

import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.ask.AskPort;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ShellContribution;
import zcd.jellyfish.api.extension.ShellContributionStatus;
import zcd.jellyfish.api.subagent.DelegationStatus;
import zcd.jellyfish.api.subagent.SubAgentPort;

import java.util.List;
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
     * 在定义好的安全点（一轮工具批次之后 / 模型本要收敛那一刻）才执行它。
     * <b>这一条没有例外</b>——动作清单上没有任何一个动作在本方法的栈上执行，因此投递之后到被取走之前，
     * 句柄一直停在 {@link zcd.jellyfish.api.action.ActionStatus#QUEUED}。
     * 在处理器里调用本方法也不会造成重入：「handler 还没返回，动作已经改了会话」这种情形不存在。
     * <p>
     * <b>投递之后不可召回</b>：没有取消入口，句柄只会走向终态。改主意只能另投一条动作把状态改回来；
     * 中止整个回合是用户主权（外壳的取消入口），不是插件动作。
     * <p>
     * <b>为什么会有失败</b>：动作只能投进<b>正在跑的那个回合</b>（见 {@link zcd.jellyfish.api.action.DeliverAs}），
     * 而这个内核里回合的边界由外壳决定。因此插件从事件订阅回调（异步投递，可能落在回合刚结束之后）
     * 或自己的线程上投递时，拿不到在途回合是<b>正常结果</b>，回报
     * {@link zcd.jellyfish.api.action.ActionStatus#FAILED} 而不是抛异常——异常会把一条「这次没赶上」
     * 变成需要 try/catch 的错误路径。
     * <p>
     * <b>失败也要按原因分流，别只按状态分流</b>：{@code FAILED} 覆盖从「没有在途回合」到「执行体抛错」
     * 好几种处境，按哪种处理要看
     * {@link zcd.jellyfish.api.action.ActionHandle#getFailureReason()}（{@code getResult()} 只给人看）。
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
     * 往目标会话写入一条扩展条目。
     * <p>
     * <b>解决什么问题</b>：插件想在会话里存自己的状态（「这次会话已经检查过哪些文件」
     * 「当前工作流的这一步是第几次」）时，此前只能塞进工具结果的元数据，因此必须先把状态
     * 伪装成一次工具调用。本方法给它一个正当的位置。
     * <p>
     * <b>key 会被加上本插件的前缀</b>（{@code pluginId} 或 {@code pluginId::子标识}），因此：
     * 插件之间互相看不见对方的条目，也无法写到别人的命名空间里；读回来看得到的是完整 key，
     * 可以据此判断哪一层写的。本插件调 {@code put} 时请传<b>不带前缀</b>的 key。
     * <p>
     * <b>它不进模型上下文</b>：与工具结果的元数据同口径——模型不需要它，界面与插件需要。
     * 但它是会话的一部分，会随会话一起落盘，因此<b>有上限</b>（单条 64 KiB、每会话 64 条、
     * key 256 字符，按内核的规范编码计算）。超限当场抛 {@link zcd.jellyfish.api.JellyfishException}
     * 且<b>不写入</b>：截断一个映射会留下「看起来完整、实际缺字段」的数据，而静默淘汰会让插件
     * 「写成功、重启后没了」。
     * <p>
     * <b>与注册不同，停止之后它不失效</b>：已落盘的条目是用户的会话数据，插件卸载后仍然保留，
     * 重新装回来还能读到。但{@code stop()} 之后本方法同样当场抛
     * {@link zcd.jellyfish.api.JellyfishException}——写入仍需存活。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @param key       不含 owner 前缀的条目名称，不可为空白
     * @param value     值，可为 {@code null}（等价空映射）
     * @throws zcd.jellyfish.api.JellyfishException 会话不存在、key 或会话标识为空白、超限，
     *                                              或插件上下文已失效时抛出
     */
    void putExtensionEntry(String sessionId, String key, Map<String, Object> value);

    /**
     * 删除本插件命名空间下的一条扩展条目。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @param key       不含 owner 前缀的条目名称，不可为空白
     * @throws zcd.jellyfish.api.JellyfishException 会话不存在或插件上下文已失效时抛出
     */
    void removeExtensionEntry(String sessionId, String key);

    /**
     * 列出<b>本插件命名空间下</b>的全部扩展条目。
     * <p>
     * <b>看不到别人的条目</b>：与写入的命名空间隔离对称。需要诊断「谁挂了东西」请看内核的
     * 会话快照（它有全部条目），不是在这里。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @return 不可变列表，未写过时为空列表；key 是完整 key
     * @throws zcd.jellyfish.api.JellyfishException 会话不存在或插件上下文已失效时抛出
     */
    List<SessionExtensionEntry> extensionEntries(String sessionId);

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

    /**
     * 向当前外壳贡献一条可渲染内容或失效提示。
     * <p>
     * <b>为什么不是复用 {@link #emit}</b>：{@code emit} 的语义是「发布一条 {@link JellyfishEvent}
     * 给订阅者」，它走事件通道（无界订阅者集合、可丢广播、无交付确认）。贡献是「给<b>当前这一个</b>
     * 外壳的一份载荷」——两者在订阅者模型与交付语义上都不同。把贡献塞进 {@code emit}
     * 会让「订阅者」与「外壳」两个概念混在一起，而外壳恰恰是刻意<b>不订阅</b>事件通道的。
     * <p>
     * <b>只入队，不阻塞调用方</b>：本方法可能在插件自己的线程上、也可能在插件的工具回调里调用，
     * 任何阻塞都会顺着那条线传下去。队列按 owner 分桶且有界，满了丢最新一条并回报
     * {@link ShellContributionStatus#DROPPED_QUEUE_FULL}。
     * <p>
     * <b>它不能起回合、不能建会话</b>：贡献是展示数据，不进模型上下文、不落盘；
     * {@link ShellContribution} 的 kind 是一份封闭清单，插件在类型上就拿不到「发一条消息」这种能力。
     * 要让模型看见东西，仍然只能 {@link #submit}。
     * <p>
     * <b>失败一律用返回值回报，只有「已停止」抛异常</b>（与 {@link #emit} 同一条存活边界）。
     * 丢弃不代表失败，不要据此重发——见 {@link ShellContributionStatus}。
     *
     * @param contribution 贡献，不可为 {@code null}
     * @return 投递结果，保证非 {@code null}
     * @throws JellyfishException 贡献为 {@code null}，或插件上下文已失效（已停止）时抛出
     */
    ShellContributionStatus present(ShellContribution contribution);

    /**
     * 获取子代理委派端口。
     * <p>
     * <b>它是出向边</b>：{@link #handle} / {@link #contribute} 是「内核回头找插件」，
     * {@link #emit} / {@link #submit} / {@link #present} 与本法是「插件往外发」。
     * 编排（一个工具里派出一批子代理、按依赖并发、收集结果）需要插件能主动驱动内核，
     * 而这是唯一对得上方向的形态。
     * <p>
     * <b>拿到的东西受全部既有约束</b>：开关、深度、单回合扇出、全局并发、墙钟与 token 预算、
     * 取消传播与 {@code task} 工具完全一致——两者走同一条代码路径。插件不应另建并发控制：
     * 并发度由内核的 governor 决定，超出的 run 在内核侧排队。
     * <p>
     * <b>它可能什么也不做</b>：内核没有装配这个能力时（旧内核、不完整装配）返回的是
     * {@link SubAgentPort#unavailable()}——{@code spawn} 给出的句柄直接带着
     * {@link DelegationStatus#REJECTED} 的结果。因此插件不必为「内核版本旧」写分支，
     * 也不会在正常路径上撞到异常。
     *
     * @return 委派端口，保证非 {@code null}
     */
    default SubAgentPort delegations() {
        return SubAgentPort.unavailable();
    }

    /**
     * 获取向用户提问的端口。
     * <p>
     * <b>它同样是出向边</b>：与 {@link #delegations()} 并列，解决的是同一类需求——插件要主动
     * 让内核做一件事（这次是「把我的问题摆到用户面前，并把答复带回来」）。提问需要模态界面，
     * 而插件在架构上碰不到界面，因此答复者只能是外壳；插件能做的只是发起一次提问并等待答复。
     * <p>
     * <b>它可能没有人回答</b>：没有交互界面的外壳（{@code -cli}）不挂答复者，{@code ask} 会立刻
     * 返回 {@link zcd.jellyfish.api.ask.AskAnswer.Status#UNAVAILABLE}。这不是异常路径，
     * 也不是安全边界——提问拿不到答案不代表哪次工具调用被拒绝。内核没有装配这个能力时（旧内核、
     * 不完整装配）返回的是 {@link AskPort#unavailable()}，因此插件不必为「内核版本旧」写分支。
     * <p>
     * <b>线程语义是阻塞</b>：{@code ask} 在调用线程上等到答复或超时。工具处理器本来就在
     * {@code react} 线程上被同步调用，因此这不会额外占用线程；但插件不应在事件回调等
     * 「不该阻塞的位置」调用它。
     *
     * @return 提问端口，保证非 {@code null}
     */
    default AskPort askUser() {
        return AskPort.unavailable();
    }
}
