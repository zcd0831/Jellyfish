package zcd.jellyfish.infra.registry;

import java.util.Objects;

/**
 * 注册表的键：请求/事件类型 + 路由键。
 * <p>
 * 路由键为 {@code null} 表示「类型级」注册：它对该类型下所有路由键都生效，
 * 因此类型级贡献与事件订阅都落在这个位置。
 * <p>
 * 包私有：这只是 {@link TypeRegistry} 的内部索引结构，两个派发策略都不需要感知它。
 *
 * @author zcd
 */
final class RegistryKey {

    /** 类型维度。 */
    private final Class<?> type;

    /** 路由键，{@code null} 表示类型级。 */
    private final String routeKey;

    /**
     * 构造键。
     *
     * @param type     类型，不可为 {@code null}
     * @param routeKey 路由键，可为 {@code null}
     */
    private RegistryKey(Class<?> type, String routeKey) {
        this.type = type;
        this.routeKey = routeKey;
    }

    /**
     * 创建键。
     *
     * @param type     类型，不可为 {@code null}
     * @param routeKey 路由键，可为 {@code null}
     * @return 注册键
     * @throws NullPointerException 类型为 {@code null} 时抛出
     */
    static RegistryKey of(Class<?> type, String routeKey) {
        return new RegistryKey(Objects.requireNonNull(type, "type must not be null"), routeKey);
    }

    /**
     * 获取类型。
     *
     * @return 类型
     */
    Class<?> getType() {
        return type;
    }

    /**
     * 获取路由键。
     *
     * @return 路由键，可能为 {@code null}
     */
    String getRouteKey() {
        return routeKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RegistryKey)) {
            return false;
        }
        RegistryKey that = (RegistryKey) other;
        return type.equals(that.type) && Objects.equals(routeKey, that.routeKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, routeKey);
    }

    @Override
    public String toString() {
        return type.getSimpleName() + "#" + (routeKey == null ? "<type-wide>" : routeKey);
    }
}
