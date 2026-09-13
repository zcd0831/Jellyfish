package zcd.jellyfish.infra.event.callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.CallbackException;
import zcd.jellyfish.api.event.callback.CallbackHandler;

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
 * 细粒度回调注册表：按 (回调类型, 路由键) 维护处理器集合，支持覆盖、顺序与按来源回收。
 * <p>
 * 与改造前的「回调注册表」相比，唯一性不再硬编码，而是由扩展点定义驱动：
 * <ul>
 *     <li>{@code unique=true}（B 提供）：同一键至多一个处理器，重复注册按 {@code override} 处理，未声明即失败；</li>
 *     <li>{@code unique=false}（A 贡献 / D 拦截 / E 策略）：同一键可有 0..N 个处理器，天然不冲突。</li>
 * </ul>
 * 注册期 fail-fast：键冲突、插件注册未开放的类型、未声明扩展点定义的类型一律直接抛错。
 * 解析期分叉为 {@link #resolveUnique(Callback)}（0 个 {@code NO_HANDLER} / 多于 1 个 {@code AMBIGUOUS_HANDLER}）
 * 与 {@link #resolveAll(Callback)}（按 {@code order} 升序，同序按注册顺序）。
 *
 * @author zcd
 */
public final class CallbackRegistry {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CallbackRegistry.class);

    /** 有序形状的调用顺序比较器：order 升序，同序按注册顺序。 */
    private static final Comparator<CallbackRegistration> ORDER_COMPARATOR =
            Comparator.comparingInt(CallbackRegistration::getOrder)
                    .thenComparingLong(CallbackRegistration::getSequence);

    /** 注册项：键 → 处理器列表（唯一形状下长度恒为 1）。 */
    private final Map<CallbackKey, CopyOnWriteArrayList<CallbackRegistration>> handlers = new ConcurrentHashMap<>();

    /** 解析缓存：回调运行时类 → 候选注册项（已按注册顺序排序）。 */
    private final Map<Class<?>, List<CallbackRegistration>> candidates = new ConcurrentHashMap<>();

    /** 注册序号，保证候选顺序稳定。 */
    private final AtomicLong sequence = new AtomicLong();

    /** 扩展点定义注册表，驱动唯一性与顺序语义。 */
    private final ExtensionPointRegistry extensionPointRegistry;

    /**
     * 构造回调注册表。
     *
     * @param extensionPointRegistry 扩展点定义注册表，不可为 {@code null}
     */
    public CallbackRegistry(ExtensionPointRegistry extensionPointRegistry) {
        this.extensionPointRegistry = Objects.requireNonNull(extensionPointRegistry,
                "extensionPointRegistry must not be null");
    }

    /**
     * 注册回调处理器。
     *
     * @param owner        来源（内置组件名或 pluginId），用于诊断与回收
     * @param fromPlugin   是否来自插件
     * @param callbackType 回调类型，不可为 {@code null}
     * @param routeKey     路由键，{@code null} 表示类型唯一
     * @param handler      回调处理器，不可为 {@code null}
     * @param options      注册选项，不可为 {@code null}
     * @param <C>          回调类型
     * @param <R>          结果类型
     * @return 注册句柄，关闭后解除本次注册
     * @throws JellyfishException 键冲突且未声明覆盖、插件注册了未开放的类型、或类型未声明扩展点定义时抛出
     */
    public <C extends Callback<R>, R> Subscription register(String owner, boolean fromPlugin, Class<C> callbackType,
                                                            String routeKey, CallbackHandler<C, R> handler,
                                                            RegisterOptions options) {
        Objects.requireNonNull(callbackType, "callbackType must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(options, "options must not be null");
        @SuppressWarnings("unchecked")
        Class<? extends Callback<?>> type = (Class<? extends Callback<?>>) (Class<?>) callbackType;
        ExtensionPointDefinition definition = extensionPointRegistry.definitionOf(type);
        if (fromPlugin && !definition.isPluginExtensible()) {
            throw new JellyfishException("plugin is not allowed to register callback type: " + callbackType.getName());
        }
        CallbackKey key = CallbackKey.of(type, routeKey);
        CopyOnWriteArrayList<CallbackRegistration> slot = handlers.computeIfAbsent(key,
                ignored -> new CopyOnWriteArrayList<CallbackRegistration>());
        CallbackRegistration registration;
        String overriddenOwner = null;
        synchronized (slot) {
            if (definition.isUnique() && !slot.isEmpty()) {
                if (!options.isOverride()) {
                    throw new JellyfishException("callback already registered: " + key
                            + " by " + slot.get(0).getOwner());
                }
                overriddenOwner = slot.get(0).getOwner();
                registration = new CallbackRegistration(owner, type, routeKey, handler, fromPlugin,
                        sequence.incrementAndGet(), options.getOrder(), overriddenOwner);
                slot.set(0, registration);
            } else {
                registration = new CallbackRegistration(owner, type, routeKey, handler, fromPlugin,
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
     * 解析回调对应的唯一处理器。
     *
     * @param callback 回调对象，不可为 {@code null}
     * @return 唯一命中的处理器
     * @throws CallbackException 命中 0 个（{@code NO_HANDLER}）或多于 1 个（{@code AMBIGUOUS_HANDLER}）时抛出
     */
    public CallbackHandler<?, ?> resolveUnique(Callback<?> callback) {
        List<CallbackRegistration> matched = matched(callback);
        if (matched.isEmpty()) {
            throw new CallbackException(CallbackException.Code.NO_HANDLER, describe(callback));
        }
        if (matched.size() > 1) {
            throw new CallbackException(CallbackException.Code.AMBIGUOUS_HANDLER, describe(callback)
                    + " candidates=[" + matched.get(0).getOwner() + ", " + matched.get(1).getOwner() + ']');
        }
        return matched.get(0).getHandler();
    }

    /**
     * 解析回调对应的全部处理器，按 {@code order} 升序、同序按注册顺序排列。
     *
     * @param callback 回调对象，不可为 {@code null}
     * @return 命中的处理器列表，可能为空
     */
    public List<CallbackHandler<?, ?>> resolveAll(Callback<?> callback) {
        List<CallbackRegistration> matched = matched(callback);
        if (matched.isEmpty()) {
            return Collections.emptyList();
        }
        matched.sort(ORDER_COMPARATOR);
        List<CallbackHandler<?, ?>> resolved = new ArrayList<>(matched.size());
        for (CallbackRegistration registration : matched) {
            resolved.add(registration.getHandler());
        }
        return resolved;
    }

    /**
     * 获取回调对象的扩展点定义。
     *
     * @param callback 回调对象，不可为 {@code null}
     * @return 扩展点定义
     */
    public ExtensionPointDefinition definitionOf(Callback<?> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        @SuppressWarnings("unchecked")
        Class<? extends Callback<?>> type = (Class<? extends Callback<?>>) callback.getClass();
        return extensionPointRegistry.definitionOf(type);
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
                        .append(key.getRouteKey() == null ? "<type-unique>" : key.getRouteKey())
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
     *
     * @param callback 回调对象
     * @return 匹配的注册项列表
     */
    private List<CallbackRegistration> matched(Callback<?> callback) {
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

    /**
     * 生成回调的可读描述，用于异常信息。
     *
     * @param callback 回调对象
     * @return 描述文本
     */
    private static String describe(Callback<?> callback) {
        return "type=" + callback.getClass().getName() + " routeKey=" + callback.getRouteKey();
    }
}
