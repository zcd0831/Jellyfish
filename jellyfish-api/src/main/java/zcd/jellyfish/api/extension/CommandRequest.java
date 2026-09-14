package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 具名命令请求：命令名 + 参数 + 会话标识，由内核在命令调用点构造，由插件或核心组件按命令名注册处理器。
 * <p>
 * 路由键即命令名，因此每条命令对应一个处理器（{@code PluginContext.handle}）。
 * <p>
 * 命令名走「数据」而不是让插件定义新的 Java 请求类型，是因为插件自定义类跨 ClassLoader 传播会导致
 * 类型互不可见，内核也无法为未知类型提供调用点。名字里的「命令」<b>不是「只有插件能用」</b>：
 * 系统命令同样注册在同一份注册表里，方向始终是内核在调用点构造本请求、处理器响应。
 * <p>
 * 输入可能是「用户敲的一行」，也可能是外壳直接给的结构化参数（Web / TUI）；两种情况都由内核归一化成
 * {@link CommandArguments} 后带进来，处理器不需要也不应该自己拆字符串。
 *
 * @author zcd
 */
public final class CommandRequest extends ExtensionRequest<CommandResult> {

    /** 命令名，也是路由键。 */
    private final String name;

    /** 解析后的参数。 */
    private final CommandArguments arguments;

    /**
     * 构造命令请求。
     *
     * @param name      命令名，不可为空白
     * @param arguments 解析后的参数，可为 {@code null}（等价 {@link CommandArguments#EMPTY}）
     * @param sessionId 会话标识，可为 {@code null}
     * @throws JellyfishException 命令名为空白时抛出
     */
    public CommandRequest(String name, CommandArguments arguments, String sessionId) {
        super(CommandResult.class, sessionId);
        if (name == null || name.trim().isEmpty()) {
            throw new JellyfishException("command name must not be blank");
        }
        this.name = name;
        this.arguments = arguments == null ? CommandArguments.EMPTY : arguments;
    }

    /**
     * 构造进程级命令请求。
     *
     * @param name      命令名，不可为空白
     * @param arguments 解析后的参数，可为 {@code null}（等价 {@link CommandArguments#EMPTY}）
     * @throws JellyfishException 命令名为空白时抛出
     */
    public CommandRequest(String name, CommandArguments arguments) {
        this(name, arguments, null);
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

    /**
     * 获取解析后的参数。
     *
     * @return 参数，保证非 {@code null}
     */
    public CommandArguments getArguments() {
        return arguments;
    }
}
