package zcd.jellyfish.infra.event.callback;

import zcd.jellyfish.api.event.callback.Callback;

import java.util.Objects;

/**
 * 回调注册表的键：回调类型 + 路由键。
 * <p>
 * 同一回调类型下，类型唯一回调（路由键为 {@code null}）至多一个处理器。
 *
 * @author zcd
 */
final class CallbackKey {

    /** 回调类型。 */
    private final Class<? extends Callback<?>> callbackType;

    /** 路由键，{@code null} 表示类型唯一。 */
    private final String routeKey;

    /**
     * 构造键。
     *
     * @param callbackType 回调类型
     * @param routeKey    路由键，可为 {@code null}
     */
    private CallbackKey(Class<? extends Callback<?>> callbackType, String routeKey) {
        this.callbackType = callbackType;
        this.routeKey = routeKey;
    }

    /**
     * 创建回调键。
     *
     * @param callbackType 回调类型，不可为 {@code null}
     * @param routeKey    路由键，可为 {@code null}
     * @return 回调键
     */
    static CallbackKey of(Class<? extends Callback<?>> callbackType, String routeKey) {
        return new CallbackKey(Objects.requireNonNull(callbackType, "callbackType must not be null"), routeKey);
    }

    /**
     * 获取回调类型。
     *
     * @return 回调类型
     */
    Class<? extends Callback<?>> getCallbackType() {
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CallbackKey)) {
            return false;
        }
        CallbackKey that = (CallbackKey) other;
        return callbackType.equals(that.callbackType) && Objects.equals(routeKey, that.routeKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callbackType, routeKey);
    }

    @Override
    public String toString() {
        return callbackType.getSimpleName() + "#" + (routeKey == null ? "<type-unique>" : routeKey);
    }
}
