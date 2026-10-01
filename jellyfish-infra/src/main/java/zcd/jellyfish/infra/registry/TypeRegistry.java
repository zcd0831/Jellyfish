package zcd.jellyfish.infra.registry;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

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
 * <b>覆盖是一条链，不是一次覆盖写</b>：唯一键上的一次显式覆盖把新登记<b>压在旧登记之上</b>，
 * 旧登记仍然留在表里，只是不再生效；查询始终只看链顶，因此对外仍是「同键唯一」。
 * 这样做是为了让覆盖<b>可逆</b>：覆盖者被注销（插件停止、主动 {@code Subscription.close()}）时，
 * 被它压住的那一层自动回到生效位置。
 * <p>
 * <b>为什么必须可逆</b>：覆盖是「用我的实现顶替你的」，而覆盖者的存活期通常短于被覆盖者
 * （插件会被停掉、会被 {@code /reload} 重启，内核的注册活到进程结束）。若覆盖是就地替换、
 * 旧登记被丢弃，那么插件一走，被它顶掉的内核工具就<b>永久消失</b>，而且登记表上看不出少了谁——
 * 表现为「装过插件之后，某个内置命令就再也没了」，重启进程才恢复。这条链把「谁被谁压住」
 * 记在 {@code HandlerRegistration.getOverriddenOwner()} 上，回收时逐层还原。
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

    /** 注册项：键 → 槽位，保持注册顺序。 */
    private final Map<RegistryKey, Slot> registrations = new ConcurrentHashMap<>();

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
        Slot slot = slotOf(key);
        HandlerRegistration registration;
        synchronized (slot) {
            slot.unique = true;
            HandlerRegistration current = slot.peek();
            if (current != null && !override) {
                throw new ExtensionException(ExtensionException.Code.DUPLICATE_HANDLER,
                        key + " already registered by " + current.getOwner());
            }
            // 覆盖是「压在链顶之上」而不是就地替换：被压住的那一层留着，覆盖者注销时自动回退
            String overriddenOwner = current == null ? null : current.getOwner();
            registration = create(owner, type, routeKey, handler, descriptor, order, overriddenOwner);
            slot.entries.add(registration);
        }
        candidates.clear();
        return registration;
    }

    /**
     * 同键 0..N 登记。
     * <p>
     * 落在唯一键上时槽位退化为共享语义（该键的全部登记都可见）：这是编程错误下的兜底，
     * 与改造前的表现一致——那时它们同样全部可见，查询会以 {@code AMBIGUOUS_HANDLER} 暴露出来。
     *
     * @param owner      来源
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
        Slot slot = slotOf(key);
        slot.unique = false;
        slot.entries.add(registration);
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
     * 列出某类型下的全部<b>生效</b>注册项，忽略路由键。
     * <p>
     * 供描述符查询使用：工具是按工具名（路由键）注册的，但清单需要一次拿到该类型下的所有处理器。
     * 被覆盖压住的层不在这里——清单要的是「现在真正能用的是谁」。
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
     * <p>
     * <b>解除唯一键的链顶就是一次回退</b>：被它压住的那一层重新生效，不必由谁重新注册。
     * 这对插件收尾尤其重要——插件停止时只回收自己的登记，被它顶替过的内核注册原地复活。
     *
     * @param registration 注册项，可为 {@code null}
     * @return 确实移除返回 {@code true}
     */
    public boolean remove(HandlerRegistration registration) {
        if (registration == null) {
            return false;
        }
        RegistryKey key = RegistryKey.of(registration.getType(), registration.getRouteKey());
        Slot slot = registrations.get(key);
        if (slot == null || !slot.entries.remove(registration)) {
            return false;
        }
        if (slot.entries.isEmpty()) {
            registrations.remove(key, slot);
        }
        candidates.clear();
        return true;
    }

    /**
     * 按来源批量回收登记。
     * <p>
     * <b>精确匹配</b>：只回收 owner 完全相等的登记。需要「连同子来源一起回收」时用
     * {@link #removeAllUnder(String, String)}，不要放宽本方法的匹配规则——
     * 它同时也服务于内核内部来源（如 {@code metrics}）的收尾，改宽会让回收范围悄悄越界。
     * <p>
     * <b>被本来源压住的层会跟着回退</b>：回收后会重算每个槽位的生效项，因此「插件注册了工具 →
     * 插件被停掉」之后，原先被它覆盖的内核工具自动恢复，不需要任何补偿动作。
     *
     * @param owner 来源标识
     * @return 回收的注册项数量
     */
    public int removeAll(String owner) {
        return removeMatching(registration -> Objects.equals(owner, registration.getOwner()));
    }

    /**
     * 按 owner 命名空间批量回收登记：命中「命名空间自身」与「命名空间下的一切子来源」。
     * <p>
     * <b>为什么需要它</b>：插件可以给同一个 {@code pluginId} 下的多个子单元各分一个 owner
     * （形如 {@code pluginId::子标识}），以获得可归因的诊断与更细的粒度。但框架回收只拿得到
     * {@code pluginId}，若只做精确匹配，子来源的注册就会在插件停止后残留成「插件已停、工具还能调」
     * 的幽灵注册。本方法把那种情形一次性收干净。
     * <p>
     * <b>匹配规则是「命名空间 + 分隔符」前缀，而不是裸前缀</b>：回收 {@code x} 不得碰
     * {@code xy} 这个毫不相干的插件，因此 {@code x} 只命中 {@code x} 与 {@code x<sep>*}，
     * 层级更深（ {@code x<sep>a<sep>b}）的也一并命中。
     * <p>
     * <b>分隔符由调用方传入</b>：命名空间是插件运行时的约定，不是注册表的约定；
     * 写死一个分隔符会让表底座替上层做主。
     *
     * @param namespace 命名空间（通常是 {@code pluginId}），不可为空白
     * @param separator 命名空间分隔符（例如 {@code ::}），不可为空
     * @return 回收的注册项数量
     * @throws JellyfishException 命名空间为空白或分隔符为空时抛出
     */
    public int removeAllUnder(String namespace, String separator) {
        if (namespace == null || namespace.trim().isEmpty()) {
            throw new JellyfishException("owner namespace must not be blank");
        }
        if (separator == null || separator.isEmpty()) {
            throw new JellyfishException("owner namespace separator must not be empty");
        }
        String prefix = namespace + separator;
        return removeMatching(registration -> {
            String owner = registration.getOwner();
            return namespace.equals(owner) || (owner != null && owner.startsWith(prefix));
        });
    }

    /**
     * 按谓词批量回收登记，供两个回收入口共用。
     * <p>
     * 逐槽位收集再整批移除：{@code CopyOnWriteArrayList} 的逐个移除每次都复制整个数组，
     * 一次注册量大的插件会退化成平方开销。
     * <p>
     * 这里回收的是槽位里的<b>全部</b>登记（含被压住的层），不是只有生效的那些：
     * 判定依据是「这条登记属于谁」，与它此刻是否生效无关——一个被压住却没被回收的层，
     * 会在覆盖者离开之后悄悄复活，那正是要避免的残留。
     *
     * @param doomed 判定命中逆汰的谓词，不可为 {@code null}
     * @return 回收的注册项数量
     */
    private int removeMatching(Predicate<HandlerRegistration> doomed) {
        int removed = 0;
        for (Map.Entry<RegistryKey, Slot> entry : registrations.entrySet()) {
            Slot slot = entry.getValue();
            List<HandlerRegistration> matched = new ArrayList<>();
            for (HandlerRegistration registration : slot.entries) {
                if (doomed.test(registration)) {
                    matched.add(registration);
                }
            }
            if (matched.isEmpty()) {
                continue;
            }
            slot.entries.removeAll(matched);
            removed += matched.size();
            if (slot.entries.isEmpty()) {
                registrations.remove(entry.getKey(), slot);
            }
        }
        if (removed > 0) {
            candidates.clear();
        }
        return removed;
    }

    /**
     * 列出全部登记项，供诊断使用。
     * <p>
     * <b>含被覆盖压住的层</b>：诊断要回答的是「现在谁注册了什么」，而「被谁压住了」正是其中的一部分。
     * 哪些此刻生效由 {@link #activeRegistrations()} 回答。
     *
     * @return 按注册顺序排列的登记项，不可修改；无登记时为空列表
     */
    public List<HandlerRegistration> registrations() {
        List<HandlerRegistration> all = new ArrayList<>();
        for (Slot slot : registrations.values()) {
            all.addAll(slot.entries);
        }
        all.sort(Comparator.comparingLong(HandlerRegistration::getSequence));
        return Collections.unmodifiableList(all);
    }

    /**
     * 列出此刻生效的登记项：唯一键只有链顶生效，共享键全部生效。
     *
     * @return 不可修改集合；无登记时为空集合
     */
    public Set<HandlerRegistration> activeRegistrations() {
        Set<HandlerRegistration> active = new HashSet<>();
        for (Slot slot : registrations.values()) {
            if (slot.unique && !slot.entries.isEmpty()) {
                active.add(slot.peek());
            } else {
                active.addAll(slot.entries);
            }
        }
        return Collections.unmodifiableSet(active);
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
    private Slot slotOf(RegistryKey key) {
        return registrations.computeIfAbsent(key, ignored -> new Slot());
    }

    /**
     * 收集查询类型对应的候选注册项，带缓存。
     * <p>
     * 只收<b>生效</b>的登记项：唯一键链上被压住的层不参与匹配，否则一次覆盖会变成
     * {@code AMBIGUOUS_HANDLER}。
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
        for (Slot slot : registrations.values()) {
            for (HandlerRegistration registration : slot.visible()) {
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

    /**
     * 一个键下的全部登记。
     * <p>
     * 两种形态由登记入口决定：唯一键是<b>覆盖链</b>（只有链顶可见，其余是被压住的层），
     * 共享键是<b>处理器集合</b>（全部可见）。合成一个类型是因为二者共用一份顺序与一段存活期，
     * 拆成两张表反而要在回收时对齐两边的键。
     */
    private static final class Slot {

        /** 登记项，按登记顺序排列；唯一键的链顶是最后一个元素。 */
        private final CopyOnWriteArrayList<HandlerRegistration> entries = new CopyOnWriteArrayList<>();

        /** 是否为唯一键（链式）；被共享登记落到同一键上时退化为 {@code false}。 */
        private volatile boolean unique = true;

        /**
         * 取链顶，即唯一键当前生效的那一条。
         *
         * @return 链顶登记项；空槽位返回 {@code null}
         */
        private HandlerRegistration peek() {
            return entries.isEmpty() ? null : entries.get(entries.size() - 1);
        }

        /**
         * 列出本槽位参与查询匹配的登记项。
         *
         * @return 唯一键只给链顶，共享键给全部
         */
        private List<HandlerRegistration> visible() {
            if (!unique) {
                return entries;
            }
            HandlerRegistration top = peek();
            return top == null ? Collections.<HandlerRegistration>emptyList()
                    : Collections.singletonList(top);
        }
    }
}
