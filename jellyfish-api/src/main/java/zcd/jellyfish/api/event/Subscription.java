package zcd.jellyfish.api.event;

/**
 * 注册句柄：用于提前解除一次注册或订阅。
 * <p>
 * 底层注册表只有「按对象反注册」，句柄语义由框架补齐。重复调用 {@link #close()} 必须幂等。
 *
 * @author zcd
 */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

    /**
     * 解除本次注册或订阅，幂等。
     */
    @Override
    void close();

    /**
     * 返回一个什么都不做的句柄，用于占位与测试。
     *
     * @return 空句柄
     */
    static Subscription noop() {
        return () -> {
            // 无注册内容，无需解除
        };
    }
}
