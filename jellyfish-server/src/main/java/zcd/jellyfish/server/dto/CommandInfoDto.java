package zcd.jellyfish.server.dto;

import zcd.jellyfish.infra.command.CommandInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令清单项：{@code GET /commands} 的返回元素。
 * <p>
 * <b>为什么把 {@link CommandInfo} 投影成 DTO 而不是直接序列化</b>：{@code CommandInfo} 是内核类型，
 * 它今天继承 {@code CommandDescriptor} 的几个 getter、明天可能多一个给 TUI 用的字段；直接暴露出去
 * 等于把 HTTP 合同绑在内核类型的演进上。投影一次，成本是一个构造方法。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandInfoDto {

    /** 命令名。 */
    private final String name;

    /** 一句话说明，可为 {@code null}。 */
    private final String summary;

    /** 用法片段，可为 {@code null}。 */
    private final String usage;

    /** 别名列表，保证非 {@code null}。 */
    private final List<String> aliases;

    /**
     * 构造清单项。
     *
     * @param name    命令名
     * @param summary 说明，可为 {@code null}
     * @param usage   用法片段，可为 {@code null}
     * @param aliases 别名列表，可为 {@code null}
     */
    public CommandInfoDto(String name, String summary, String usage, List<String> aliases) {
        this.name = name;
        this.summary = summary;
        this.usage = usage;
        this.aliases = aliases == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(aliases));
    }

    /**
     * 把内核清单项投影成 DTO。
     *
     * @param info 内核清单项，不可为 {@code null}
     * @return 清单项 DTO
     */
    public static CommandInfoDto of(CommandInfo info) {
        return new CommandInfoDto(info.getName(), info.getSummary(), info.getUsage(), info.getAliases());
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
     * 获取说明。
     *
     * @return 说明，可能为 {@code null}
     */
    public String getSummary() {
        return summary;
    }

    /**
     * 获取用法片段。
     *
     * @return 用法片段，可能为 {@code null}
     */
    public String getUsage() {
        return usage;
    }

    /**
     * 获取别名列表。
     *
     * @return 别名列表，保证非 {@code null}
     */
    public List<String> getAliases() {
        return aliases;
    }
}
