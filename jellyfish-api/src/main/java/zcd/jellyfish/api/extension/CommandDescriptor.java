package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令名片：注册命令处理器时与处理器一起落表的「命令说明」。
 * <p>
 * <b>不含命令名</b>：名字就是注册时的路由键（{@code PluginContext.handle(CommandRequest.class, name, ...)}
 * 的 {@code name}），因此不存在「名片上的名字与路由键不一致」这本错账——清单渲染时内核从注册项取名字、
 * 从名片取说明，两者天然对齐。
 * <p>
 * 与处理器一起落表的好处：命令清单不需要第二份目录，插件下架时名片随注册一起消失。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandDescriptor {

    /** 命令前缀字符：别名不允许带它，因为输入阶段已把它剥掉，带了就是永不命中的死别名。 */
    private static final char COMMAND_PREFIX_CHAR = '/';

    /** 一句话用途说明，帮助与菜单里显示。 */
    private final String summary;

    /** 用法片段（如 {@code "<agentId>"}），不含命令名本身。 */
    private final String usage;

    /** 别名列表，不含前缀与命令名本身。 */
    private final List<String> aliases;

    /**
     * 构造命令名片。
     *
     * @param summary 一句话说明，可为 {@code null}
     * @param usage   用法片段，可为 {@code null}
     * @param aliases 别名列表，可为 {@code null}
     * @throws JellyfishException 别名为空白、含空白字符，或以命令前缀（{@code /}）开头时抛出
     */
    public CommandDescriptor(String summary, String usage, List<String> aliases) {
        this.summary = summary;
        this.usage = usage;
        this.aliases = normalizeAliases(aliases);
    }

    /**
     * 获取一句话说明。
     *
     * @return 一句话说明，未提供时为 {@code null}
     */
    public String getSummary() {
        return summary;
    }

    /**
     * 获取用法片段。
     *
     * @return 用法片段（不含命令名），未提供时为 {@code null}
     */
    public String getUsage() {
        return usage;
    }

    /**
     * 获取别名列表。
     *
     * @return 不可变别名列表，保证非 {@code null}
     */
    public List<String> getAliases() {
        return aliases;
    }

    @Override
    public String toString() {
        return "CommandDescriptor{aliases=" + aliases + '}';
    }

    /**
     * 校验并去重别名。
     * <p>
     * 重复声明同一条命令自己的别名不构成冲突（别名索引里它仍指向同一条命令），因此去重而不是报错，
     * 免得让插件的启动因为一个无意义的重复项失败。
     *
     * @param aliases 原始别名列表，可为 {@code null}
     * @return 不可变别名列表，保证非 {@code null}
     * @throws JellyfishException 别名不合法时抛出
     */
    private static List<String> normalizeAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<String>();
        for (String alias : aliases) {
            unique.add(requireAlias(alias));
        }
        return Collections.unmodifiableList(new ArrayList<String>(unique));
    }

    /**
     * 校验单个别名。
     *
     * @param alias 别名，可为 {@code null}
     * @return 校验通过的别名
     * @throws JellyfishException 别名为空白、含空白字符，或以命令前缀开头时抛出
     */
    private static String requireAlias(String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            throw new JellyfishException("command alias must not be blank");
        }
        if (alias.charAt(0) == COMMAND_PREFIX_CHAR) {
            throw new JellyfishException("command alias must not start with the command prefix: " + alias);
        }
        for (int i = 0; i < alias.length(); i++) {
            if (Character.isWhitespace(alias.charAt(i))) {
                throw new JellyfishException("command alias must not contain whitespace: " + alias);
            }
        }
        return alias;
    }
}
