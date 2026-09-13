package zcd.jellyfish.infra.event;

import com.google.common.eventbus.Subscribe;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.CallbackException;
import zcd.jellyfish.api.event.callback.CallbackHandler;
import zcd.jellyfish.api.event.callback.ExtensionPoint;
import zcd.jellyfish.api.event.callback.PermissionCheckRequest;
import zcd.jellyfish.api.event.callback.PluginRequest;
import zcd.jellyfish.api.event.callback.ToolCallRequest;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.callback.ExtensionPointDefinition;

import java.util.List;

/**
 * 回调分发器：Guava 层的唯一回调入口。
 * <p>
 * 每类核心回调一个 {@code @Subscribe} 方法，内部统一走 {@link #invoke(Callback)}：
 * 查细粒度注册表 → 按扩展点定义的 {@code resultArity} 分叉为单结果 / 多结果 → 执行处理器 → 回填应答槽。
 * 这样把「Guava 的 {@code @Subscribe} 只能返回 void」这道边界关在框架代码里，插件代码永远是 {@code return / throw}。
 * <p>
 * 新增核心回调类型时，必须在这里显式加一个方法——这是有意保留的登记点，可 review、可加约定、可加指标。
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
     * 派发权限检查回调。
     *
     * @param callback 权限检查回调
     */
    @Subscribe
    void onPermissionCheck(PermissionCheckRequest callback) {
        dispatchCallback(callback);
    }

    /**
     * 派发插件具名命令回调。
     *
     * @param callback 插件具名命令回调
     */
    @Subscribe
    void onPluginRequest(PluginRequest callback) {
        dispatchCallback(callback);
    }

    /**
     * 按扩展点定义 fork 单结果 / 多结果派发。
     *
     * @param callback 回调对象
     * @param <R>      结果类型
     */
    <R> void dispatchCallback(Callback<R> callback) {
        ExtensionPointDefinition definition = callbackRegistry.definitionOf(callback);
        if (definition.getResultArity() == ExtensionPoint.ResultArity.MANY) {
            invokeAll(callback, definition);
        } else {
            invokeOne(callback, definition);
        }
    }

    /**
     * 单结果派发：保留原有的三道防线（{@code NO_HANDLER} / 处理器异常回传 / {@code NO_RESPONSE}）。
     *
     * @param callback   回调对象
     * @param definition 扩展点定义
     * @param <R>        结果类型
     */
    private <R> void invokeOne(Callback<R> callback, ExtensionPointDefinition definition) {
        CallbackHandler<Callback<R>, R> handler = resolveUnique(callback, definition);
        if (handler == null) {
            return;
        }
        try {
            Object result = handler.handle(callback);
            callbackReplies.complete(callback, result);
            stats.dispatchedCallbacks.increment();
        } catch (Exception e) {
            callbackReplies.fail(callback, e);
            stats.failedCallbacks.increment();
        }
    }

    /**
     * 多结果派发：按注册顺序逐个调用，收集成功结果，失败语义由扩展点定义决定。
     * <p>
     * fail-open（A / D）丢弃失败处理器的结果并继续；fail-closed（E）记录首个失败并停止后续调用。
     *
     * @param callback   回调对象
     * @param definition 扩展点定义
     * @param <R>        结果类型
     */
    private <R> void invokeAll(Callback<R> callback, ExtensionPointDefinition definition) {
        List<CallbackHandler<?, ?>> handlers = callbackRegistry.resolveAll(callback);
        if (handlers.isEmpty()) {
            handleEmpty(callback, definition);
            return;
        }
        boolean failClosed = definition.getFailurePolicy() == ExtensionPoint.FailurePolicy.FAIL_CLOSED;
        for (CallbackHandler<?, ?> raw : handlers) {
            if (!callOne(callback, raw, failClosed)) {
                return;
            }
        }
    }

    /**
     * 执行单个处理器并回填结果。
     *
     * @param callback   回调对象
     * @param raw        处理器
     * @param failClosed 失败是否让整次调用失败
     * @param <R>        结果类型
     * @return 是否应继续调用后续处理器
     */
    @SuppressWarnings("unchecked")
    private <R> boolean callOne(Callback<R> callback, CallbackHandler<?, ?> raw, boolean failClosed) {
        CallbackHandler<Callback<R>, R> handler = (CallbackHandler<Callback<R>, R>) raw;
        try {
            Object result = handler.handle(callback);
            callbackReplies.complete(callback, result);
            stats.dispatchedCallbacks.increment();
            return true;
        } catch (Exception e) {
            stats.failedCallbacks.increment();
            if (failClosed) {
                callbackReplies.fail(callback, e);
                return false;
            }
            return true;
        }
    }

    /**
     * 处理空表：REQUIRED 硬失败，OPTIONAL 视为无贡献（单结果形状回填 {@code null} 以区分于未应答）。
     *
     * @param callback   回调对象
     * @param definition 扩展点定义
     * @param <R>        结果类型
     */
    private <R> void handleEmpty(Callback<R> callback, ExtensionPointDefinition definition) {
        stats.noHandlerCallbacks.increment();
        if (definition.getEmptyPolicy() == ExtensionPoint.EmptyPolicy.REQUIRED) {
            stats.failedCallbacks.increment();
            callbackReplies.fail(callback, new CallbackException(CallbackException.Code.NO_HANDLER,
                    "type=" + callback.getClass().getName() + " routeKey=" + callback.getRouteKey()));
            return;
        }
        if (definition.getResultArity() == ExtensionPoint.ResultArity.ONE) {
            callbackReplies.complete(callback, null);
        }
    }

    /**
     * 解析唯一处理器；空表按定义降级，解析失败把错误码回填到应答槽并记账。
     *
     * @param callback   回调对象
     * @param definition 扩展点定义
     * @param <R>        结果类型
     * @return 命中的处理器；无需调用时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private <R> CallbackHandler<Callback<R>, R> resolveUnique(Callback<R> callback, ExtensionPointDefinition definition) {
        try {
            return (CallbackHandler<Callback<R>, R>) callbackRegistry.resolveUnique(callback);
        } catch (CallbackException e) {
            if (e.getCode() == CallbackException.Code.NO_HANDLER) {
                handleEmpty(callback, definition);
                return null;
            }
            if (e.getCode() == CallbackException.Code.AMBIGUOUS_HANDLER) {
                stats.ambiguousHandlerCallbacks.increment();
            }
            stats.failedCallbacks.increment();
            callbackReplies.fail(callback, e);
            return null;
        }
    }
}
