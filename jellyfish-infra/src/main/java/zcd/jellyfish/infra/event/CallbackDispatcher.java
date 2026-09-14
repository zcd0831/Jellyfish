package zcd.jellyfish.infra.event;

import com.google.common.eventbus.Subscribe;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;

import java.util.List;

/**
 * 回调分发器：Guava 层的唯一回调入口。
 * <p>
 * 每类核心回调一个 {@code @Subscribe} 方法，内部统一走 {@link #dispatchCallback(ExtensionRequest)}：
 * 查细粒度注册表 → 匹配到的处理器按 {@code order} 升序依次内联调用 → 逐个回填应答槽。
 * 这样把「Guava 的 {@code @Subscribe} 只能返回 void」这道边界关在框架代码里，插件代码永远是 {@code return / throw}。
 * <p>
 * <b>每类回调一个方法是有意保留的登记点</b>：新增核心回调类型必须在这里加一个方法，好处是
 * 可 review、可加约定、可加指标。这条「类型必须显式登记」的代价换来的是回调不会静默地
 * 无人处理——缺登记时 Guava 会把它当死事件，而这里每个类型都有明确的落点。
 * <p>
 * 多处理器语义：匹配到几个就按顺序调用几个，每个结果都回填应答槽（{@code invoke} 读到的是首个结果；
 * 需要聚合多个结果的调用点让回调自带结果容器承接）。<b>首个处理器失败即停止后续调用并把异常记入应答槽</b>，
 * 由 {@code invoke} 在读取时上抛——工具调用这类场景不允许处理器失败被静默吞掉。
 *
 * @author zcd
 */
final class CallbackDispatcher {

    /** 细粒度回调注册表。 */
    private final CallbackRegistry callbackRegistry;

    /** 回调应答槽。 */
    private final CallbackReplies callbackReplies;

    /** 指标。 */
    private final EventBusStats stats;

    /**
     * 构造回调分发器。
     *
     * @param callbackRegistry 细粒度回调注册表
     * @param callbackReplies  回调应答槽
     * @param stats            指标
     */
    CallbackDispatcher(CallbackRegistry callbackRegistry, CallbackReplies callbackReplies, EventBusStats stats) {
        this.callbackRegistry = callbackRegistry;
        this.callbackReplies = callbackReplies;
        this.stats = stats;
    }

    /**
     * 派发工具调用回调。
     *
     * @param callback 工具调用回调
     */
    @Subscribe
    void onToolCall(ToolCallRequest callback) {
        dispatchCallback(callback);
    }

    /**
     * 派发具名命令回调。
     *
     * @param callback 具名命令回调
     */
    @Subscribe
    void onCommandRequest(CommandRequest callback) {
        dispatchCallback(callback);
    }

    /**
     * 按注册顺序把回调交给全部匹配的处理器。
     *
     * @param callback 回调对象
     * @param <R>      结果类型
     */
    <R> void dispatchCallback(ExtensionRequest<R> callback) {
        List<ExtensionHandler<?, ?>> handlers = callbackRegistry.resolve(callback);
        if (handlers.isEmpty()) {
            handleNoHandler(callback);
            return;
        }
        for (ExtensionHandler<?, ?> raw : handlers) {
            if (!callOne(callback, raw)) {
                return;
            }
        }
    }

    /**
     * 执行单个处理器并回填结果。
     *
     * @param callback 回调对象
     * @param raw      处理器
     * @param <R>      结果类型
     * @return 是否应继续调用后续处理器
     */
    @SuppressWarnings("unchecked")
    private <R> boolean callOne(ExtensionRequest<R> callback, ExtensionHandler<?, ?> raw) {
        ExtensionHandler<ExtensionRequest<R>, R> handler = (ExtensionHandler<ExtensionRequest<R>, R>) raw;
        try {
            Object result = handler.handle(callback);
            callbackReplies.complete(callback, result);
            stats.dispatchedCallbacks.increment();
            return true;
        } catch (Exception e) {
            stats.failedCallbacks.increment();
            callbackReplies.fail(callback, e);
            return false;
        }
    }

    /**
     * 无处理器即硬失败：回调是「请求-响应」语义，没有任何处理器意味着调用方拿不到结果。
     *
     * @param callback 回调对象
     * @param <R>      结果类型
     */
    private <R> void handleNoHandler(ExtensionRequest<R> callback) {
        stats.noHandlerCallbacks.increment();
        stats.failedCallbacks.increment();
        callbackReplies.fail(callback, new ExtensionException(ExtensionException.Code.NO_HANDLER,
                "type=" + callback.getClass().getName() + " routeKey=" + callback.getRouteKey()));
    }
}
