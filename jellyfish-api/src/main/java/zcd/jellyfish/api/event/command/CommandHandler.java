package zcd.jellyfish.api.event.command;

/**
 * 类型化命令处理器。
 * <p>
 * 声明 {@code throws Exception} 是刻意的：插件常见实现会调用 JDBC 等抛受检异常的 API，
 * 允许受检异常可以显著降低插件作者的样板代码；分发器统一捕获并回填为命令失败。
 *
 * @param <C> 命令类型
 * @param <R> 结果类型
 * @author zcd
 */
@FunctionalInterface
public interface CommandHandler<C extends Command<R>, R> {

    /**
     * 处理命令。
     *
     * @param command 命令对象，只读
     * @return 命令结果，不允许为 {@code null}
     * @throws Exception 处理失败时抛出，由分发器转为命令失败
     */
    R handle(C command) throws Exception;
}
