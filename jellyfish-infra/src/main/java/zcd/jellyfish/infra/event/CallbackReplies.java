package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.CallbackException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 回调应答槽：按 {@link Callback#getCallbackId()} 保管在途回调的结果。
 * <p>
 * 应答槽从 api 的 {@link Callback} 下沉到这里，是为了让插件只看到纯请求数据，无法误用「回填结果」这类框架内部能力；
 * 索引直接复用回调自带的 {@code callbackId}，不额外引入映射层。
 * <p>
 * 与改造前的单结果应答槽相比，本类同时支持两种结果数量：
 * <ul>
 *     <li>{@link #await(Callback)}：{@code resultArity=ONE}，返回单个结果，保留原有的三道防线；</li>
 *     <li>{@link #awaitAll(Callback)}：{@code resultArity=MANY}，返回按处理器调用顺序排列的全部成功结果。</li>
 * </ul>
 * 失败语义由调用方（分发器）决定是否调用 {@link #fail(Callback, Throwable)}：fail-open 形状丢弃失败项并继续，
 * fail-closed 形状记录失败并在读取时上抛。
 *
 * @author zcd
 */
final class CallbackReplies {

    /** 在途应答槽：回调标识 → 结果槽。 */
    private final ConcurrentMap<String, Slot> pending = new ConcurrentHashMap<>();

    /**
     * 登记一条在途回调。
     *
     * @param callback 回调对象
     */
    void open(Callback<?> callback) {
        pending.put(callback.getCallbackId(), new Slot());
    }

    /**
     * 回填一条结果。
     *
     * @param callback 回调对象
     * @param result   结果对象，须满足 {@link Callback#getResultType()}
     * @throws CallbackException 结果类型不匹配时抛出
     */
    void complete(Callback<?> callback, Object result) {
        Class<?> resultType = callback.getResultType();
        if (result != null && !resultType.isInstance(result)) {
            throw new CallbackException(CallbackException.Code.RESULT_TYPE_MISMATCH,
                    "expected " + resultType.getName() + " but got " + result.getClass().getName()
                            + " for callback " + callback.getCallbackId());
        }
        Slot slot = pending.get(callback.getCallbackId());
        if (slot != null) {
            slot.add(result);
        }
    }

    /**
     * 回填回调失败。
     *
     * @param callback 回调对象
     * @param cause    失败原因
     */
    void fail(Callback<?> callback, Throwable cause) {
        Slot slot = pending.get(callback.getCallbackId());
        if (slot != null) {
            slot.fail(cause);
        }
    }

    /**
     * 取出单个结果。
     * <p>
     * 处理器抛出的运行时异常原样抛出；受检异常包装为 {@link JellyfishException}，因为该签名不应声明受检异常。
     *
     * @param callback 回调对象
     * @param <R>      结果类型
     * @return 回调结果
     * @throws CallbackException 未应答时抛出
     */
    @SuppressWarnings("unchecked")
    <R> R await(Callback<R> callback) {
        Slot slot = requireSlot(callback);
        return (R) slot.single(callback);
    }

    /**
     * 取出按处理器调用顺序排列的全部成功结果。
     *
     * @param callback 回调对象
     * @param <R>      结果类型
     * @return 结果列表，可能为空
     * @throws CallbackException 未应答时抛出
     */
    @SuppressWarnings("unchecked")
    <R> List<R> awaitAll(Callback<R> callback) {
        Slot slot = requireSlot(callback);
        return (List<R>) (List<?>) slot.all(callback);
    }

    /**
     * 回收应答槽。
     *
     * @param callback 回调对象
     */
    void close(Callback<?> callback) {
        pending.remove(callback.getCallbackId());
    }

    /**
     * 获取回调对应的结果槽。
     *
     * @param callback 回调对象
     * @return 结果槽
     * @throws CallbackException 槽已回收或从未登记时抛出
     */
    private Slot requireSlot(Callback<?> callback) {
        Slot slot = pending.get(callback.getCallbackId());
        if (slot == null) {
            throw new CallbackException(CallbackException.Code.NO_RESPONSE,
                    "reply slot missing: " + callback.getCallbackId());
        }
        return slot;
    }

    /**
     * 单次回调的结果槽：收集结果并记录首个失败。
     *
     * @author zcd
     */
    private static final class Slot {

        /** 已成功的结果，按回填顺序排列。 */
        private final List<Object> results = new ArrayList<>();

        /** 首个失败原因，未失败时为 {@code null}。 */
        private Throwable failure;

        /**
         * 追加一条成功结果。
         *
         * @param result 结果对象，可为 {@code null}
         */
        synchronized void add(Object result) {
            results.add(result);
        }

        /**
         * 记录失败，仅保留首个失败原因。
         *
         * @param cause 失败原因
         */
        synchronized void fail(Throwable cause) {
            if (failure == null) {
                failure = cause;
            }
        }

        /**
         * 读取单个结果。
         *
         * @param callback 回调对象，用于异常信息
         * @return 单个结果
         * @throws CallbackException 未应答时抛出
         */
        synchronized Object single(Callback<?> callback) {
            if (failure != null) {
                throw rethrow(failure);
            }
            if (results.isEmpty()) {
                throw new CallbackException(CallbackException.Code.NO_RESPONSE,
                        "callback not answered: " + callback.getCallbackId());
            }
            return results.get(0);
        }

        /**
         * 读取全部结果。
         *
         * @param callback 回调对象
         * @return 结果快照
         */
        synchronized List<Object> all(Callback<?> callback) {
            if (failure != null) {
                throw rethrow(failure);
            }
            if (results.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(results);
        }

        /**
         * 把失败原因还原为可抛出的异常：运行时异常与 Error 原样抛出，受检异常包装。
         *
         * @param cause 失败原因
         * @return 可抛出的运行时异常
         */
        private static RuntimeException rethrow(Throwable cause) {
            if (cause instanceof RuntimeException) {
                return (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            return new JellyfishException("callback handler failed", cause);
        }
    }
}
