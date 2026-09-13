package zcd.jellyfish.api.event.callback;

/**
 * 类型化回调处理器。
 * <p>
 * 声明 {@code throws Exception} 是刻意的：插件常见实现会调用 JDBC 等抛受检异常的 API，
 * 允许受检异常可以显著降低插件作者的样板代码；分发器统一捕获并回填为回调失败。
 *
 * @param <C> 回调类型
 * @param <R> 结果类型
 * @author zcd
 */
@FunctionalInterface
public interface CallbackHandler<C extends Callback<R>, R> {

    /**
     * 处理回调。
     *
     * @param callback 回调对象，只读
     * @return 回调结果，不允许为 {@code null}
     * @throws Exception 处理失败时抛出，由分发器转为回调失败
     */
    R handle(C callback) throws Exception;
}
