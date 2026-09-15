package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 命令候选查询请求：问「这条命令在不带参数时，有哪些取值可以让用户挑」。
 * <p>
 * <b>与 {@link CommandRequest} 分开</b>：执行一条命令可能有副作用（建会话、切模型……），
 * 而外壳在「用户刚选中命令、还没决定参数」时不希望产生任何副作用。
 * 因此候选查询是一条<b>只读</b>路径，由命令按需注册处理器（{@code PluginContext.handle}），
 * 没注册就表示「这条命令没有可选值」。
 * <p>
 * 路由键同样是命令名，因此一条命令可以同时拥有「执行处理器」与「候选处理器」，
 * 两者是不同的请求类型、互不覆盖。
 *
 * @author zcd
 */
public final class CommandOptionRequest extends ExtensionRequest<CommandOptions> {

    /** 命令名，也是路由键。 */
    private final String name;

    /**
     * 构造候选查询请求。
     *
     * @param name      命令名，不可为空白
     * @param sessionId 会话标识，可为 {@code null}
     * @throws JellyfishException 命令名为空白时抛出
     */
    public CommandOptionRequest(String name, String sessionId) {
        super(CommandOptions.class, sessionId);
        if (name == null || name.trim().isEmpty()) {
            throw new JellyfishException("command name must not be blank");
        }
        this.name = name;
    }

    /**
     * 构造进程级候选查询请求。
     *
     * @param name 命令名，不可为空白
     * @throws JellyfishException 命令名为空白时抛出
     */
    public CommandOptionRequest(String name) {
        this(name, null);
    }

    @Override
    public String getRouteKey() {
        return name;
    }

    /**
     * 获取命令名。
     *
     * @return 命令名
     */
    public String getName() {
        return name;
    }
}
