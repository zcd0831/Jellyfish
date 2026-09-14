package zcd.jellyfish.api.extension;

/**
 * 类型化扩展点处理器。
 * <p>
 * 声明 {@code throws Exception} 是刻意的：插件常见实现会调用 JDBC 等抛受检异常的 API，
 * 允许受检异常可以显著降低插件作者的样板代码；内核在调用点统一决策是否捕获、是否继续后续调用。
 *
 * @param <C> 请求类型
 * @param <R> 结果类型
 * @author zcd
 */
@FunctionalInterface
public interface ExtensionHandler<C extends ExtensionRequest<R>, R> {

    /**
     * 处理请求。
     *
     * @param request 请求对象，只读
     * @return 处理结果，不允许为 {@code null}
     * @throws Exception 处理失败时抛出，由调用点决定上抛还是记账
     */
    R handle(C request) throws Exception;
}
