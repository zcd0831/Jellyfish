package zcd.jellyfish.infra.event.command;

import zcd.jellyfish.api.event.command.Command;

import java.util.Objects;

/**
 * 命令注册表的键：命令类型 + 路由键。
 * <p>
 * 同一命令类型下，类型唯一命令（路由键为 {@code null}）至多一个处理器。
 *
 * @author zcd
 */
final class CommandKey {

    /** 命令类型。 */
    private final Class<? extends Command<?>> commandType;

    /** 路由键，{@code null} 表示类型唯一。 */
    private final String routeKey;

    /**
     * 构造键。
     *
     * @param commandType 命令类型
     * @param routeKey    路由键，可为 {@code null}
     */
    private CommandKey(Class<? extends Command<?>> commandType, String routeKey) {
        this.commandType = commandType;
        this.routeKey = routeKey;
    }

    /**
     * 创建命令键。
     *
     * @param commandType 命令类型，不可为 {@code null}
     * @param routeKey    路由键，可为 {@code null}
     * @return 命令键
     */
    static CommandKey of(Class<? extends Command<?>> commandType, String routeKey) {
        return new CommandKey(Objects.requireNonNull(commandType, "commandType must not be null"), routeKey);
    }

    /**
     * 获取命令类型。
     *
     * @return 命令类型
     */
    Class<? extends Command<?>> getCommandType() {
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CommandKey)) {
            return false;
        }
        CommandKey that = (CommandKey) other;
        return commandType.equals(that.commandType) && Objects.equals(routeKey, that.routeKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandType, routeKey);
    }

    @Override
    public String toString() {
        return commandType.getSimpleName() + "#" + (routeKey == null ? "<type-unique>" : routeKey);
    }
}
