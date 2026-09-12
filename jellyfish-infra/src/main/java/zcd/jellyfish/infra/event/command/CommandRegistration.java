package zcd.jellyfish.infra.event.command;

import zcd.jellyfish.api.event.command.CommandHandler;

/**
 * 命令注册项：处理器 + 来源 + 路由信息 + 注册序号。
 * <p>
 * {@code sequence} 用于派发时稳定排序，保证候选顺序与注册顺序一致。
 *
 * @author zcd
 */
final class CommandRegistration {

    /** 来源（内置组件名或 pluginId），用于诊断与按来源回收。 */
    private final String owner;

    /** 命令类型。 */
    private final Class<?> commandType;

    /** 路由键，{@code null} 表示类型唯一。 */
    private final String routeKey;

    /** 命令处理器。 */
    private final CommandHandler<?, ?> handler;

    /** 是否来自插件。 */
    private final boolean fromPlugin;

    /** 注册序号，单调递增。 */
    private final long sequence;

    /** 被本次注册覆盖掉的来源，未发生覆盖时为 {@code null}。 */
    private final String overriddenOwner;

    /**
     * 构造注册项。
     *
     * @param owner           来源
     * @param commandType     命令类型
     * @param routeKey        路由键，可为 {@code null}
     * @param handler         命令处理器
     * @param fromPlugin      是否来自插件
     * @param sequence        注册序号
     * @param overriddenOwner 被覆盖的来源，可为 {@code null}
     */
    CommandRegistration(String owner, Class<?> commandType, String routeKey, CommandHandler<?, ?> handler,
                        boolean fromPlugin, long sequence, String overriddenOwner) {
        this.owner = owner;
        this.commandType = commandType;
        this.routeKey = routeKey;
        this.handler = handler;
        this.fromPlugin = fromPlugin;
        this.sequence = sequence;
        this.overriddenOwner = overriddenOwner;
    }

    /**
     * 获取来源。
     *
     * @return 来源
     */
    String getOwner() {
        return owner;
    }

    /**
     * 获取命令类型。
     *
     * @return 命令类型
     */
    Class<?> getCommandType() {
        return commandType;
    }

    /**
     * 获取路由键。
     *
     * @return 路由键，可能为 {@code null}
     */
    String getRouteKey() {
        return routeKey;
    }

    /**
     * 获取命令处理器。
     *
     * @return 命令处理器
     */
    CommandHandler<?, ?> getHandler() {
        return handler;
    }

    /**
     * 判断是否来自插件。
     *
     * @return 来自插件返回 {@code true}
     */
    boolean isFromPlugin() {
        return fromPlugin;
    }

    /**
     * 获取注册序号。
     *
     * @return 注册序号
     */
    long getSequence() {
        return sequence;
    }

    /**
     * 获取被覆盖的来源。
     *
     * @return 被覆盖的来源，未发生覆盖时为 {@code null}
     */
    String getOverriddenOwner() {
        return overriddenOwner;
    }
}
