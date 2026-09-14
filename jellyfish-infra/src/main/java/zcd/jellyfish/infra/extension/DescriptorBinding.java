package zcd.jellyfish.infra.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 描述符绑定：一次注册的「路由键 + 名片 + 来源」只读投影。
 * <p>
 * 与 {@link HandlerBinding} 的分工：那个给「要归因的执行调用点」用（owner + handler），本类给
 * 「要清单的查询调用点」用（owner + routeKey + descriptor）——命令帮助与菜单就属于后者：
 * 它要按命令名列出说明，而不是去执行谁。
 * <p>
 * <b>描述符允许为 {@code null}</b>：不给命令写名片的插件确实存在，而它注册的命令一样可执行，
 * 清单类调用点不能把它漏掉，因此缺名片表达成「字段为空」而不是「这一项不存在」。
 * <p>
 * 由 {@link ExtensionRegistry#descriptorBindings} 构造，外部只读。
 *
 * @param <D> 描述符类型
 * @author zcd
 */
public final class DescriptorBinding<D> {

    /** 来源（内核组件名或 pluginId），用于诊断与冲突归因。 */
    private final String owner;

    /** 路由键（命令名 / 工具名），{@code null} 表示类型级注册。 */
    private final String routeKey;

    /** 描述符，未提供时为 {@code null}。 */
    private final D descriptor;

    /**
     * 构造描述符绑定。
     *
     * @param owner      来源，不可为空白
     * @param routeKey   路由键，可为 {@code null}（类型级注册）
     * @param descriptor 描述符，可为 {@code null}
     * @throws JellyfishException 来源为空白时抛出
     */
    public DescriptorBinding(String owner, String routeKey, D descriptor) {
        if (owner == null || owner.trim().isEmpty()) {
            throw new JellyfishException("descriptor binding owner must not be blank");
        }
        this.owner = owner;
        this.routeKey = routeKey;
        this.descriptor = descriptor;
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
     * 获取路由键。
     *
     * @return 路由键，类型级注册时为 {@code null}
     */
    public String getRouteKey() {
        return routeKey;
    }

    /**
     * 获取描述符。
     *
     * @return 描述符，未提供时为 {@code null}
     */
    public D getDescriptor() {
        return descriptor;
    }

    @Override
    public String toString() {
        return "DescriptorBinding{owner=" + owner + ", routeKey="
                + (routeKey == null ? "<type-wide>" : routeKey) + '}';
    }
}
