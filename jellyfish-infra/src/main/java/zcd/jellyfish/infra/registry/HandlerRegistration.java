package zcd.jellyfish.infra.registry;

/**
 * 注册项：一次注册在表里的全部事实。
 * <p>
 * 处理器与描述符都是不透明对象：同步策略往 {@code handler} 里放 {@code ExtensionHandler}、
 * 往 {@code descriptor} 里放工具描述符；异步策略把「过滤谓词 + 监听器」封装成一个订阅者对象放进
 * {@code handler}。注册表不认识它们的具体类型，只负责保管与按序取回。
 * <p>
 * {@code sequence} 由注册表分配，用于同 {@code order} 时保持注册顺序稳定；
 * {@code order} 由插件声明，只对同步类型级贡献有意义。
 * <p>
 * 由 {@link TypeRegistry} 构造，外部只读。
 *
 * @author zcd
 */
public final class HandlerRegistration {

    /** 来源（内核组件名或 pluginId），用于诊断与按来源回收。 */
    private final String owner;

    /** 类型维度。 */
    private final Class<?> type;

    /** 路由键，{@code null} 表示类型级。 */
    private final String routeKey;

    /** 处理器对象，具体类型由派发策略决定。 */
    private final Object handler;

    /** 处理器描述符，未提供时为 {@code null}。 */
    private final Object descriptor;

    /** 注册序号，单调递增。 */
    private final long sequence;

    /** 调用顺序，仅对同步类型级贡献有意义。 */
    private final int order;

    /** 被本次注册覆盖掉的来源，未发生覆盖时为 {@code null}。 */
    private final String overriddenOwner;

    /**
     * 构造注册项。
     *
     * @param owner           来源
     * @param type            类型
     * @param routeKey        路由键，可为 {@code null}
     * @param handler         处理器对象
     * @param descriptor      处理器描述符，可为 {@code null}
     * @param sequence        注册序号
     * @param order           调用顺序
     * @param overriddenOwner 被覆盖的来源，可为 {@code null}
     */
    HandlerRegistration(String owner, Class<?> type, String routeKey, Object handler, Object descriptor,
                        long sequence, int order, String overriddenOwner) {
        this.owner = owner;
        this.type = type;
        this.routeKey = routeKey;
        this.handler = handler;
        this.descriptor = descriptor;
        this.sequence = sequence;
        this.order = order;
        this.overriddenOwner = overriddenOwner;
    }

    /**
     * 获取来源。
     *
     * @return 来源
     */
    public String getOwner() {
        return owner;
    }

    /**
     * 获取类型。
     *
     * @return 类型
     */
    public Class<?> getType() {
        return type;
    }

    /**
     * 获取路由键。
     *
     * @return 路由键，可能为 {@code null}
     */
    public String getRouteKey() {
        return routeKey;
    }

    /**
     * 获取处理器对象。
     *
     * @return 处理器对象
     */
    public Object getHandler() {
        return handler;
    }

    /**
     * 获取处理器描述符。
     *
     * @return 处理器描述符，未提供时为 {@code null}
     */
    public Object getDescriptor() {
        return descriptor;
    }

    /**
     * 获取注册序号。
     *
     * @return 注册序号
     */
    public long getSequence() {
        return sequence;
    }

    /**
     * 获取调用顺序。
     *
     * @return 调用顺序
     */
    public int getOrder() {
        return order;
    }

    /**
     * 获取被覆盖的来源。
     *
     * @return 被覆盖的来源，未发生覆盖时为 {@code null}
     */
    public String getOverriddenOwner() {
        return overriddenOwner;
    }

    @Override
    public String toString() {
        return type.getSimpleName() + "#" + (routeKey == null ? "<type-wide>" : routeKey)
                + "{owner=" + owner + ", order=" + order + '}';
    }
}
