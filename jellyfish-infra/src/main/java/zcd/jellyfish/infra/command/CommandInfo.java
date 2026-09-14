package zcd.jellyfish.infra.command;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandDescriptor;

import java.util.Collections;
import java.util.List;

/**
 * 结构化清单项：外壳自渲染菜单 / 下拉 / RPC 返回值时的最小视图。
 * <p>
 * 与 api 侧 {@link CommandDescriptor} 的分工：名片是插件写的（别名 / 说明 / 用法），本类是内核投影出来的
 * （命令名 + 名片）。因此「插件没写名片」表达成可空字段，并在这里给出空安全的便捷读取，
 * 免得每个外壳各写一遍判空。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandInfo {

    /** 命令名，即注册时的路由键。 */
    private final String name;

    /** 命令名片，插件未提供时为 {@code null}。 */
    private final CommandDescriptor descriptor;

    /**
     * 构造清单项。
     *
     * @param name       命令名，不可为空白
     * @param descriptor 命令名片，可为 {@code null}
     * @throws JellyfishException 命令名为空白时抛出
     */
    public CommandInfo(String name, CommandDescriptor descriptor) {
        if (name == null || name.trim().isEmpty()) {
            throw new JellyfishException("command name must not be blank");
        }
        this.name = name;
        this.descriptor = descriptor;
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
     * 获取命令名片。
     *
     * @return 命令名片，未提供时为 {@code null}
     */
    public CommandDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * 获取别名列表。
     *
     * @return 别名列表，无名片时为空列表
     */
    public List<String> getAliases() {
        return descriptor == null ? Collections.<String>emptyList() : descriptor.getAliases();
    }

    /**
     * 获取一句话说明。
     *
     * @return 一句话说明，无名片时为 {@code null}
     */
    public String getSummary() {
        return descriptor == null ? null : descriptor.getSummary();
    }

    /**
     * 获取用法片段。
     *
     * @return 用法片段，无名片时为 {@code null}
     */
    public String getUsage() {
        return descriptor == null ? null : descriptor.getUsage();
    }

    @Override
    public String toString() {
        return "CommandInfo{name=" + name + '}';
    }
}
