package zcd.jellyfish.infra.event.callback;

import zcd.jellyfish.api.extension.ExtensionHandler;

/**
 * 回调注册项：处理器 + 来源 + 路由信息 + 注册序号 + 调用顺序。
 * <p>
 * {@code sequence} 用于保证候选顺序与注册顺序一致；{@code order} 由插件声明，
 * 调用时按 {@code order} 升序、同序回退到 {@code sequence}。
 *
 * @author zcd
 */
final class CallbackRegistration {

    /** 来源（内置组件名或 pluginId），用于诊断与按来源回收。 */
    private final String owner;

    /** 回调类型。 */
    private final Class<?> callbackType;

    /** 路由键，{@code null} 表示类型唯一。 */
    private final String routeKey;

    /** 回调处理器。 */
    private final ExtensionHandler<?, ?> handler;

    /** 处理器描述符（如工具描述符），未提供时为 {@code null}。 */
    private final Object descriptor;

    /** 注册序号，单调递增。 */
    private final long sequence;

    /** 调用顺序，仅对类型级贡献有意义。 */
    private final int order;

    /** 被本次注册覆盖掉的来源，未发生覆盖时为 {@code null}。 */
    private final String overriddenOwner;

    /**
     * 构造注册项。
     *
     * @param owner           来源
     * @param callbackType    回调类型
     * @param routeKey        路由键，可为 {@code null}
     * @param handler         回调处理器
     * @param descriptor      处理器描述符，可为 {@code null}
     * @param sequence        注册序号
     * @param order           调用顺序
     * @param overriddenOwner 被覆盖的来源，可为 {@code null}
     */
    CallbackRegistration(String owner, Class<?> callbackType, String routeKey, ExtensionHandler<?, ?> handler,
                         Object descriptor, long sequence, int order, String overriddenOwner) {
        this.owner = owner;
        this.callbackType = callbackType;
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
    String getOwner() {
        return owner;
    }

    /**
     * 获取回调类型。
     *
     * @return 回调类型
     */
    Class<?> getCallbackType() {
        return callbackType;
    }

    /**
     * 获取路由键。
     *
     * @return 路由键，可能为 {@code null}
     */
    String getRouteKey() {
        return routeKey;
    }

    /**
     * 获取回调处理器。
     *
     * @return 回调处理器
     */
    ExtensionHandler<?, ?> getHandler() {
        return handler;
    }


    /**
     * 获取处理器描述符。
     *
     * @return 处理器描述符，未提供时为 {@code null}
     */
    Object getDescriptor() {
        return descriptor;
    }

    /**
     * 获取注册序号。
     *
     * @return 注册序号
     */
    long getSequence() {
        return sequence;
    }

    /**
     * 获取调用顺序。
     *
     * @return 调用顺序
     */
    int getOrder() {
        return order;
    }

    /**
     * 获取被覆盖的来源。
     *
     * @return 被覆盖的来源，未发生覆盖时为 {@code null}
     */
    String getOverriddenOwner() {
        return overriddenOwner;
    }
}
