package zcd.jellyfish.api.event;

/**
 * 注册句柄：用于提前解除一次注册或订阅。
 * <p>
 * 底层注册表只有「按对象反注册」，句柄语义由框架补齐。重复调用 {@link #close()} 必须幂等。
 * <p>
 * <b>本接口刻意不继承 {@code AutoCloseable}</b>，不要加回去：注册与订阅的<b>拥有者是框架</b>，
 * 它按 {@code owner}（插件场景即 {@code pluginId}）批量回收，插件既不需要也不应自行反注册，
 * 这里的句柄只是「提前解除」的便利出口，被丢弃是正常用法。
 * {@code AutoCloseable} 的契约却是「不关闭即泄漏、调用方必须用 try-with-resources 兜住」，
 * 与真实语义相反：一旦继承，所有 {@code handle} / {@code contribute} / {@code observe} 的
 * 调用点（包括仓库外的插件）都会被 IDE 报「used without try-with-resources」，
 * 而那只会在每个调用点制造噪声，并不能换来任何真实的资源安全。
 *
 * @author zcd
 */
@FunctionalInterface
public interface Subscription {

    /**
     * 解除本次注册或订阅，幂等。
     */
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
