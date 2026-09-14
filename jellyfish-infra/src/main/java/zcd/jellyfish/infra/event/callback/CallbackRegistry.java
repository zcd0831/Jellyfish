package zcd.jellyfish.infra.event.callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ExtensionHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 细粒度回调注册表：按 (回调类型, 路由键) 维护处理器，支持覆盖、顺序与按来源回收。
 * <p>
 * 两条注册路径对应两种调用面：
 * <ul>
 *     <li>{@link #register}：同键唯一，重复注册按 {@code override} 处理，未声明即失败——工具、具名命令走这里；</li>
 *     <li>{@link #contribute}：类型级 0..N，天然不冲突——收集式贡献走这里。</li>
 * </ul>
 * 注册期 fail-fast：键冲突、占用了不存在的回调类型一律直接抛错。
 * 解析只有一个入口 {@link #resolve(ExtensionRequest)}：按 {@code order} 升序、同序按注册顺序返回<b>全部</b>匹配项，
 * 「唯一」不再是解析期的属性，而是调用面自己的约定。
 *
 * @author zcd
 */
public final class CallbackRegistry {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CallbackRegistry.class);

    /** 调用顺序比较器：order 升序，同序按注册顺序。 */
    private static final Comparator<CallbackRegistration> ORDER_COMPARATOR =
            Comparator.comparingInt(CallbackRegistration::getOrder)
                    .thenComparingLong(CallbackRegistration::getSequence);

    /** 注册项：键 → 处理器列表。 */
    private final Map<CallbackKey, CopyOnWriteArrayList<CallbackRegistration>> handlers = new ConcurrentHashMap<>();

    /** 解析缓存：回调运行时类 → 候选注册项（已按注册顺序排序）。 */
    private final Map<Class<?>, List<CallbackRegistration>> candidates = new ConcurrentHashMap<>();

    /** 注册序号，保证候选顺序稳定。 */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 注册唯一处理器：同一 (回调类型, 路由键) 至多一个。
     *
     * @param owner        来源（内置组件名或 pluginId），用于诊断与回收
     * @param callbackType 回调类型，不可为 {@code null}
     * @param routeKey     路由键，不可为 {@code null}
     * @param descriptor   处理器描述符（如工具描述符），可为 {@code null}
     * @param handler      回调处理器，不可为 {@code null}
     * @param options      注册选项，不可为 {@code null}
     * @param <C>          回调类型
     * @param <R>          结果类型
     * @return 注册句柄，关闭后解除本次注册
     * @throws JellyfishException 键已占用且未声明覆盖时抛出
     */
    public <C extends ExtensionRequest<R>, R> Subscription register(String owner, Class<C> callbackType, String routeKey,
                                                            Object descriptor, ExtensionHandler<C, R> handler,
                                                            RegisterOptions options) {
        return doRegister(owner, callbackType, routeKey, descriptor, handler, options, true);
    }

    /**
     * 注册无描述符的唯一处理器，等价于描述符传 {@code null}。
     *
     * @param owner        来源
     * @param callbackType 回调类型
     * @param routeKey     路由键
     * @param handler      回调处理器
     * @param options      注册选项
     * @param <C>          回调类型
     * @param <R>          结果类型
     * @return 注册句柄
     */
    public <C extends ExtensionRequest<R>, R> Subscription register(String owner, Class<C> callbackType, String routeKey,
                                                                    ExtensionHandler<C, R> handler,
                                                                    RegisterOptions options) {
        return register(owner, callbackType, routeKey, null, handler, options);
    }

    /**
     * 注册类型级贡献：同一回调类型允许 0..N 个处理器。
     *
     * @param owner        来源（内置组件名或 pluginId），用于诊断与回收
     * @param callbackType 回调类型，不可为 {@code null}
     * @param descriptor   处理器描述符，可为 {@code null}
     * @param handler      回调处理器，不可为 {@code null}
     * @param options      注册选项，不可为 {@code null}
     * @param <C>          回调类型
     * @param <R>          结果类型
     * @return 注册句柄，关闭后解除本次注册
     */
    public <C extends ExtensionRequest<R>, R> Subscription contribute(String owner, Class<C> callbackType,
                                                              Object descriptor, ExtensionHandler<C, R> handler,
                                                              RegisterOptions options) {
        return doRegister(owner, callbackType, null, descriptor, handler, options, false);
    }

    /**
     * 注册类型级贡献，等价于描述符传 {@code null}。
     *
     * @param owner        来源
     * @param callbackType 回调类型
     * @param handler      回调处理器
     * @param options      注册选项
     * @param <C>          回调类型
     * @param <R>          结果类型
     * @return 注册句柄
     */
    public <C extends ExtensionRequest<R>, R> Subscription contribute(String owner, Class<C> callbackType,
                                                                      ExtensionHandler<C, R> handler,
                                                                      RegisterOptions options) {
        return contribute(owner, callbackType, null, handler, options);
    }

    /**
     * 解析回调对应的全部处理器，按 {@code order} 升序、同序按注册顺序排列。
     *
     * @param callback 回调对象，不可为 {@code null}
     * @return 命中的处理器列表，可能为空
     */
    public List<ExtensionHandler<?, ?>> resolve(ExtensionRequest<?> callback) {
        List<CallbackRegistration> matched = matched(callback);
        if (matched.isEmpty()) {
            return Collections.emptyList();
        }
        matched.sort(ORDER_COMPARATOR);
        List<ExtensionHandler<?, ?>> resolved = new ArrayList<>(matched.size());
        for (CallbackRegistration registration : matched) {
            resolved.add(registration.getHandler());
        }
        return resolved;
    }

    /**
     * 按来源批量回收注册。
     *
     * @param owner 来源标识
     * @return 回收的注册数量
     */
    public int unregisterAll(String owner) {
        int removed = 0;
        for (Map.Entry<CallbackKey, CopyOnWriteArrayList<CallbackRegistration>> entry : handlers.entrySet()) {
            CopyOnWriteArrayList<CallbackRegistration> slot = entry.getValue();
            List<CallbackRegistration> doomed = new ArrayList<>();
            for (CallbackRegistration registration : slot) {
                if (Objects.equals(owner, registration.getOwner())) {
                    doomed.add(registration);
                }
            }
            if (doomed.isEmpty()) {
                continue;
            }
            slot.removeAll(doomed);
            removed += doomed.size();
            if (slot.isEmpty()) {
                handlers.remove(entry.getKey(), slot);
            }
        }
        if (removed > 0) {
            candidates.clear();
        }
        return removed;
    }

    /**
     * 判断注册表是否为空。
     *
     * @return 无任何注册返回 {@code true}
     */
    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    /**
     * 清空注册表，用于总线关闭时释放引用。
     */
    public void clear() {
        handlers.clear();
        candidates.clear();
    }

    /**
     * 渲染诊断视图。
     *
     * @return 多行文本，无注册时返回空串
     */
    public String render() {
        if (isEmpty()) {
            return "";
        }
        List<CallbackKey> keys = new ArrayList<>(handlers.keySet());
        keys.sort(Comparator.comparing((CallbackKey key) -> key.getCallbackType().getName())
                .thenComparing(key -> key.getRouteKey() == null ? "" : key.getRouteKey()));
        StringBuilder builder = new StringBuilder("callbacks:\n");
        for (CallbackKey key : keys) {
            CopyOnWriteArrayList<CallbackRegistration> slot = handlers.get(key);
            if (slot == null) {
                continue;
            }
            for (CallbackRegistration registration : slot) {
                builder.append("  ").append(key.getCallbackType().getSimpleName()).append("  ")
                        .append(key.getRouteKey() == null ? "<type-wide>" : key.getRouteKey())
                        .append("  order=").append(registration.getOrder())
                        .append("  <- ").append(registration.getOwner());
                if (registration.getOverriddenOwner() != null) {
                    builder.append(" (overrides ").append(registration.getOverriddenOwner()).append(')');
                }
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * 执行一次注册。
     *
     * @param owner        来源
     * @param callbackType 回调类型
     * @param routeKey     路由键，{@code null} 表示类型级
     * @param descriptor   处理器描述符，可为 {@code null}
     * @param handler      回调处理器
     * @param options      注册选项
     * @param unique       是否同键唯一
     * @param <C>          回调类型
     * @param <R>          结果类型
     * @return 注册句柄
     * @throws JellyfishException 唯一槽已被占用且未声明覆盖时抛出
     */
    private <C extends ExtensionRequest<R>, R> Subscription doRegister(String owner, Class<C> callbackType, String routeKey,
                                                               Object descriptor, ExtensionHandler<C, R> handler,
                                                               RegisterOptions options, boolean unique) {
        Objects.requireNonNull(callbackType, "callbackType must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(options, "options must not be null");
        @SuppressWarnings("unchecked")
        Class<? extends ExtensionRequest<?>> type = (Class<? extends ExtensionRequest<?>>) (Class<?>) callbackType;
        CallbackKey key = CallbackKey.of(type, routeKey);
        CopyOnWriteArrayList<CallbackRegistration> slot = handlers.computeIfAbsent(key,
                ignored -> new CopyOnWriteArrayList<CallbackRegistration>());
        CallbackRegistration registration;
        String overriddenOwner = null;
        synchronized (slot) {
            if (unique && !slot.isEmpty()) {
                if (!options.isOverride()) {
                    throw new JellyfishException("callback already registered: " + key
                            + " by " + slot.get(0).getOwner());
                }
                overriddenOwner = slot.get(0).getOwner();
                registration = new CallbackRegistration(owner, type, routeKey, handler, descriptor,
                        sequence.incrementAndGet(), options.getOrder(), overriddenOwner);
                slot.set(0, registration);
            } else {
                registration = new CallbackRegistration(owner, type, routeKey, handler, descriptor,
                        sequence.incrementAndGet(), options.getOrder(), null);
                slot.add(registration);
            }
        }
        candidates.clear();
        if (overriddenOwner != null) {
            LOG.info("回调处理器被覆盖: {} 从 {} 改为 {}", key, overriddenOwner, owner);
        }
        CallbackRegistration registered = registration;
        return () -> unregister(key, registered);
    }

    /**
     * 解除一次注册，仅当该注册项仍在表中时生效。
     *
     * @param key          回调键
     * @param registration 注册时的注册项
     */
    private void unregister(CallbackKey key, CallbackRegistration registration) {
        CopyOnWriteArrayList<CallbackRegistration> slot = handlers.get(key);
        if (slot == null) {
            return;
        }
        if (slot.remove(registration)) {
            if (slot.isEmpty()) {
                handlers.remove(key, slot);
            }
            candidates.clear();
        }
    }

    /**
     * 收集回调运行时类对应的候选注册项，带缓存。
     *
     * @param callbackClass 回调运行时类
     * @return 候选列表，已按注册顺序排序且不可修改
     */
    private List<CallbackRegistration> candidatesFor(Class<?> callbackClass) {
        List<CallbackRegistration> cached = candidates.get(callbackClass);
        if (cached != null) {
            return cached;
        }
        List<CallbackRegistration> collected = new ArrayList<>();
        for (CopyOnWriteArrayList<CallbackRegistration> slot : handlers.values()) {
            for (CallbackRegistration registration : slot) {
                if (registration.getCallbackType().isAssignableFrom(callbackClass)) {
                    collected.add(registration);
                }
            }
        }
        collected.sort(Comparator.comparingLong(CallbackRegistration::getSequence));
        List<CallbackRegistration> immutable = Collections.unmodifiableList(collected);
        List<CallbackRegistration> raced = candidates.putIfAbsent(callbackClass, immutable);
        return raced == null ? immutable : raced;
    }

    /**
     * 收集匹配路由键的候选注册项。
     * <p>
     * 路由键为 {@code null} 的注册项是类型级贡献，对所有路由键都生效；其余按精确匹配。
     *
     * @param callback 回调对象
     * @return 匹配的注册项列表
     */
    private List<CallbackRegistration> matched(ExtensionRequest<?> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        String routeKey = callback.getRouteKey();
        List<CallbackRegistration> matched = new ArrayList<>();
        for (CallbackRegistration candidate : candidatesFor(callback.getClass())) {
            if (candidate.getRouteKey() == null || candidate.getRouteKey().equals(routeKey)) {
                matched.add(candidate);
            }
        }
        return matched;
    }
}
