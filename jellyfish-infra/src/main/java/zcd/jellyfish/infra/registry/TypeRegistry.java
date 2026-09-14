package zcd.jellyfish.infra.registry;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionException;

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
 * 类型注册表底座：整个内核只有这一份「类型 + 路由键 → 有序处理器集合」的表。
 * <p>
 * 同步派发策略（{@code ExtensionRegistry}）与异步派发策略（{@code EventChannel}）都建立在本表之上，
 * 差异只在取用方式：前者按「类型 + 路由键」精确取用并在调用点线程内联执行，后者按类型取用并交给线程池广播。
 * 表本身不认识处理器语义，也不决定任何调用顺序以外的调度行为。
 * <p>
 * 两种登记入口对应两种调用面：
 * <ul>
 *     <li>{@link #registerUnique}：同键至多一个，冲突时抛
 *     {@link ExtensionException.Code#DUPLICATE_HANDLER}，除非显式声明覆盖——工具、具名命令走这里；</li>
 *     <li>{@link #registerShared}：同键 0..N，天然不冲突——类型级贡献、事件订阅走这里。</li>
 * </ul>
 * <b>唯一性由登记入口决定，不是键的属性</b>：同一个 (类型, 路由键) 在唯一入口下唯一，在共享入口下可多个。
 * <p>
 * 匹配规则：注册的类型只要 {@code isAssignableFrom} 查询类型即命中，因此订阅父类型能收到子类型；
 * 路由键为 {@code null} 的类型级注册对所有路由键生效。
 * <p>
 * <b>只保证有序，不保证只调一次、不负责编排</b>：解析结果按 {@code order} 升序、同序按注册顺序返回，
 * 调用几个、怎么合并结果由调用方决定。
 *
 * @author zcd
 */
public final class TypeRegistry {

    /** 调用顺序比较器：order 升序，同序按注册顺序。 */
    private static final Comparator<HandlerRegistration> ORDER_COMPARATOR =
            Comparator.comparingInt(HandlerRegistration::getOrder)
                    .thenComparingLong(HandlerRegistration::getSequence);

    /** 注册项：键 → 处理器列表，保持注册顺序。 */
    private final Map<RegistryKey, CopyOnWriteArrayList<HandlerRegistration>> registrations = new ConcurrentHashMap<>();

    /** 解析缓存：查询类型 → 命中注册项（已按 order + sequence 排序）。 */
    private final Map<Class<?>, List<HandlerRegistration>> candidates = new ConcurrentHashMap<>();

    /** 注册序号，保证候选顺序稳定。 */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 同键唯一登记。
     *
     * @param owner      来源（内核组件名或 pluginId），不可为空白
     * @param type       类型，不可为 {@code null}
     * @param routeKey   路由键，可为 {@code null}（类型级）
     * @param handler    处理器对象，不可为 {@code null}
     * @param descriptor 处理器描述符，可为 {@code null}
     * @param order      调用顺序
     * @param override   键已被占用时是否允许覆盖
     * @return 本次登记产生的注册项
     * @throws ExtensionException 来源为空白，或键已被占用且未声明覆盖时抛出
     * @throws NullPointerException 类型或处理器为 {@code null} 时抛出
     */
    public HandlerRegistration registerUnique(String owner, Class<?> type, String routeKey, Object handler,
                                             Object descriptor, int order, boolean override) {
        RegistryKey key = keyOf(owner, type, routeKey, handler);
        CopyOnWriteArrayList<HandlerRegistration> slot = slotOf(key);
        HandlerRegistration registration;
        String overriddenOwner = null;
        synchronized (slot) {
            if (!slot.isEmpty() && !override) {
                throw new ExtensionException(ExtensionException.Code.DUPLICATE_HANDLER,
                        key + " already registered by " + slot.get(0).getOwner());
            }
            if (!slot.isEmpty()) {
                overriddenOwner = slot.get(0).getOwner();
            }
            registration = create(owner, type, routeKey, handler, descriptor, order, overriddenOwner);
            if (overriddenOwner == null) {
                slot.add(registration);
            } else {
                // 覆盖是整条替换：同键唯一语义下旧处理器不再保留
                slot.set(0, registration);
            }
        }
        candidates.clear();
        return registration;
    }

    /**
     * 同键 0..N 登记。
     *
     * @param owner      来源（内核组件名或 pluginId），不可为空白
     * @param type       类型，不可为 {@code null}
     * @param routeKey   路由键，可为 {@code null}（类型级）
     * @param handler    处理器对象，不可为 {@code null}
     * @param descriptor 处理器描述符，可为 {@code null}
     * @param order      调用顺序
     * @return 本次登记产生的注册项
     * @throws ExtensionException 来源为空白时抛出
     * @throws NullPointerException 类型或处理器为 {@code null} 时抛出
     */
    public HandlerRegistration registerShared(String owner, Class<?> type, String routeKey, Object handler,
                                              Object descriptor, int order) {
        RegistryKey key = keyOf(owner, type, routeKey, handler);
        HandlerRegistration registration = create(owner, type, routeKey, handler, descriptor, order, null);
        slotOf(key).add(registration);
        candidates.clear();
        return registration;
    }

    /**
     * 解析命中的注册项：类型宽匹配 + 路由键精确匹配（类型级注册对所有路由键生效）。
     *
     * @param type     查询类型（通常是请求的运行时类），不可为 {@code null}
     * @param routeKey 查询路由键，可为 {@code null}
     * @return 按 order 升序、同序按注册顺序排列的注册项，不可修改；无命中时为空列表
     */
    public List<HandlerRegistration> resolve(Class<?> type, String routeKey) {
        List<HandlerRegistration> matched = new ArrayList<>();
        for (HandlerRegistration registration : candidatesFor(type)) {
            if (registration.getRouteKey() == null || registration.getRouteKey().equals(routeKey)) {
                matched.add(registration);
            }
        }
        return Collections.unmodifiableList(matched);
    }

    /**
     * 列出某类型下的全部注册项，忽略路由键。
     * <p>
     * 供描述符查询使用：工具是按工具名（路由键）注册的，但清单需要一次拿到该类型下的所有处理器。
     *
     * @param type 查询类型，不可为 {@code null}
     * @return 按 order 升序、同序按注册顺序排列的注册项，不可修改；无命中时为空列表
     */
    public List<HandlerRegistration> registrationsOf(Class<?> type) {
        return candidatesFor(type);
    }

    /**
     * 取出某类型下全部非空描述符。
     *
     * @param type           查询类型，不可为 {@code null}
     * @param descriptorType 期望的描述符类型，不可为 {@code null}
     * @param <D>            描述符类型
     * @return 描述符列表，顺序与 {@link #registrationsOf(Class)} 一致；无描述符时为空列表
     * @throws ExtensionException 存在非空描述符但类型不符时抛出
     */
    public <D> List<D> descriptorsOf(Class<?> type, Class<D> descriptorType) {
        Objects.requireNonNull(descriptorType, "descriptorType must not be null");
        List<D> descriptors = new ArrayList<>();
        for (HandlerRegistration registration : registrationsOf(type)) {
            Object descriptor = registration.getDescriptor();
            if (descriptor == null) {
                // 描述符缺省是合法状态（例如内部注册的处理器不需要向模型暴露元信息）
                continue;
            }
            if (!descriptorType.isInstance(descriptor)) {
                throw new ExtensionException(ExtensionException.Code.DESCRIPTOR_TYPE_MISMATCH,
                        "expected " + descriptorType.getName() + " but got " + descriptor.getClass().getName()
                                + " for " + registration);
            }
            descriptors.add(descriptorType.cast(descriptor));
        }
        return Collections.unmodifiableList(descriptors);
    }

    /**
     * 解除一次登记。
     *
     * @param registration 注册项，可为 {@code null}
     * @return 确实移除返回 {@code true}
     */
    public boolean remove(HandlerRegistration registration) {
        if (registration == null) {
            return false;
        }
        RegistryKey key = RegistryKey.of(registration.getType(), registration.getRouteKey());
        CopyOnWriteArrayList<HandlerRegistration> slot = registrations.get(key);
        if (slot == null || !slot.remove(registration)) {
            return false;
        }
        if (slot.isEmpty()) {
            registrations.remove(key, slot);
        }
        candidates.clear();
        return true;
    }

    /**
     * 按来源批量回收登记。
     *
     * @param owner 来源标识
     * @return 回收的注册项数量
     */
    public int removeAll(String owner) {
        int removed = 0;
        for (Map.Entry<RegistryKey, CopyOnWriteArrayList<HandlerRegistration>> entry : registrations.entrySet()) {
            CopyOnWriteArrayList<HandlerRegistration> slot = entry.getValue();
            List<HandlerRegistration> doomed = new ArrayList<>();
            for (HandlerRegistration registration : slot) {
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
                registrations.remove(entry.getKey(), slot);
            }
        }
        if (removed > 0) {
            candidates.clear();
        }
        return removed;
    }

    /**
     * 列出全部注册项，供诊断使用。
     *
     * @return 按注册顺序排列的注册项，不可修改；无登记时为空列表
     */
    public List<HandlerRegistration> registrations() {
        List<HandlerRegistration> all = new ArrayList<>();
        for (CopyOnWriteArrayList<HandlerRegistration> slot : registrations.values()) {
            all.addAll(slot);
        }
        all.sort(Comparator.comparingLong(HandlerRegistration::getSequence));
        return Collections.unmodifiableList(all);
    }

    /**
     * 判断注册表是否为空。
     *
     * @return 无任何登记返回 {@code true}
     */
    public boolean isEmpty() {
        return registrations.isEmpty();
    }

    /**
     * 清空注册表，用于关闭时释放引用。
     */
    public void clear() {
        registrations.clear();
        candidates.clear();
    }

    /**
     * 创建注册项并分配注册序号。
     *
     * @param owner           来源
     * @param type            类型
     * @param routeKey        路由键
     * @param handler         处理器对象
     * @param descriptor      处理器描述符
     * @param order           调用顺序
     * @param overriddenOwner 被覆盖的来源
     * @return 注册项
     */
    private HandlerRegistration create(String owner, Class<?> type, String routeKey, Object handler,
                                      Object descriptor, int order, String overriddenOwner) {
        return new HandlerRegistration(owner, type, routeKey, handler, descriptor,
                sequence.incrementAndGet(), order, overriddenOwner);
    }

    /**
     * 校验参数并构造键。
     *
     * @param owner   来源
     * @param type    类型
     * @param routeKey 路由键
     * @param handler 处理器对象
     * @return 注册键
     */
    private static RegistryKey keyOf(String owner, Class<?> type, String routeKey, Object handler) {
        if (owner == null || owner.trim().isEmpty()) {
            throw new JellyfishException("registration owner must not be blank");
        }
        Objects.requireNonNull(handler, "handler must not be null");
        return RegistryKey.of(type, routeKey);
    }

    /**
     * 获取键对应的槽位，不存在时创建。
     *
     * @param key 注册键
     * @return 槽位
     */
    private CopyOnWriteArrayList<HandlerRegistration> slotOf(RegistryKey key) {
        return registrations.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<HandlerRegistration>());
    }

    /**
     * 收集查询类型对应的候选注册项，带缓存。
     *
     * @param type 查询类型
     * @return 已按 order + sequence 排序的注册项，不可修改
     */
    private List<HandlerRegistration> candidatesFor(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        List<HandlerRegistration> cached = candidates.get(type);
        if (cached != null) {
            return cached;
        }
        List<HandlerRegistration> collected = new ArrayList<>();
        for (CopyOnWriteArrayList<HandlerRegistration> slot : registrations.values()) {
            for (HandlerRegistration registration : slot) {
                if (registration.getType().isAssignableFrom(type)) {
                    collected.add(registration);
                }
            }
        }
        collected.sort(ORDER_COMPARATOR);
        List<HandlerRegistration> immutable = Collections.unmodifiableList(collected);
        List<HandlerRegistration> raced = candidates.putIfAbsent(type, immutable);
        return raced == null ? immutable : raced;
    }

    /**
     * 创建诊断快照。
     *
     * @return 快照
     */
    public RegistrySnapshot snapshot() {
        return RegistrySnapshot.of(this);
    }
}
