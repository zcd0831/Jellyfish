package zcd.jellyfish.infra.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.infra.extension.DescriptorBinding;
import zcd.jellyfish.infra.extension.ExtensionRegistry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 命令域服务：输入解析 / 别名解析 / 分发 / 结构化清单 / 帮助渲染。
 * <p>
 * <b>只注入 {@link ExtensionRegistry}</b>：命令域不持有会话、不发事件、不读配置——会话标识由调用方随
 * 输入一起带进来，命令的副作用由处理器自己写回对应域服务。
 * <p>
 * <b>不注册任何处理器</b>：系统命令与插件命令都经 {@code ExtensionRegistry.handle(...)} 落同一份注册表，
 * 本类只做「按名字取出来执行」与「按类型取名字与名片」两件事。命令名即路由键，别名与用法来自随处理器
 * 一起落表的 {@link CommandDescriptor}，因此不存在第二份命令清单。
 * <p>
 * <b>对外壳中立</b>：原文入口服务输入框（CLI / TUI / Web 文本框），结构化入口服务直接调用
 * （Web API / TUI 菜单），结构化清单服务自排版的外壳；本类不假设输出到哪里，也不假设谁在调。
 * <p>
 * <b>无索引无缓存</b>：每次执行 / 渲染都从注册表现算，因此插件热部署后立刻可见；命令是人类节奏的调用，
 * O(命令数) 的重建可以忽略。
 * <p>
 * <b>公共入口不抛异常</b>：用户输入错误与插件缺陷都被翻译成 {@link CommandResult} 或清单 / 文案
 * （另打 WARN 日志）。唯一的例外是 api 侧值对象的构造期校验——那是编程错误，立即抛。
 * <p>
 * <b>TODO 系统命令未落地</b>：{@code /help} {@code /model} {@code /agent} {@code /mode} {@code /new}
 * {@code /exit} 都还没实现，本类也不注册任何处理器。续做时建议落在 {@code core}（owner = {@code "core"}），
 * 经 {@code handle(CommandRequest.class, name, descriptor, handler)} 落同一份注册表：{@code /help} 直接调
 * {@link #renderHelp()}，其余由持有 {@code SessionManager} / {@code ModelManager} / {@code AgentManager}
 * 的组件实现，副作用写回对应域服务（外壳执行后读域服务拿状态，不从结果文本里反解）。
 *
 * @author zcd
 */
@Singleton
public class CommandManager {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CommandManager.class);

    /** 命令前缀：只有以它开头的输入才进入命令域。 */
    public static final String COMMAND_PREFIX = "/";

    /** 帮助左列宽度：命令名与用法对齐到这里，便于肉眼扫读。 */
    private static final int HELP_LEFT_WIDTH = 30;

    /** 无名片时的说明占位。 */
    private static final String NO_SUMMARY = "（未提供说明）";

    /** 同步扩展点策略：命令处理器与命令清单都从同一份注册表取。 */
    private final ExtensionRegistry extensions;

    /**
     * 构造命令域服务。
     *
     * @param extensions 同步扩展点策略，不可为 {@code null}
     */
    @Inject
    public CommandManager(ExtensionRegistry extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
    }

    /**
     * 判断一行输入在语法上是不是命令（以 {@link #COMMAND_PREFIX} 开头且命令名非空）。
     * <p>
     * 只做语法判定、不查注册表：外壳据此决定「走命令还是走 LLM」，
     * 「有没有这条命令」由 {@link #execute} 的 {@code UNKNOWN} 结果回答。
     *
     * @param input 用户输入原文，可为 {@code null}
     * @return 语法上是命令返回 {@code true}
     */
    public boolean isCommand(String input) {
        return CommandLineParser.parse(input).isCommand();
    }

    /**
     * 列出全部命令的结构化清单，按命令名升序。
     * <p>
     * 给「要自己排版」的外壳用：TUI 菜单、Web 下拉、Server 的 RPC 返回值；文本外壳直接用
     * {@link #renderHelp()}，它就是基于本方法渲染的。
     * <p>
     * 没有名片的命令也会出现在清单里（名片字段为 {@code null}）——它一样可执行，
     * 漏掉会让菜单少一条命令。
     *
     * @return 不可修改的命令清单，按命令名升序；没有任何命令时为空列表
     * @throws ExtensionException 存在非空但不是 {@link CommandDescriptor} 的描述符时抛出（插件注册缺陷，
     *                            静默返回空清单会让人误以为「没有命令」）
     */
    public List<CommandInfo> commands() {
        List<CommandInfo> infos = new ArrayList<CommandInfo>();
        for (DescriptorBinding<CommandDescriptor> binding
                : extensions.descriptorBindings(CommandRequest.class, CommandDescriptor.class)) {
            // 路由键为空的是类型级注册，它不是一条命令
            if (binding.getRouteKey() != null) {
                infos.add(new CommandInfo(binding.getRouteKey(), binding.getDescriptor()));
            }
        }
        infos.sort(Comparator.comparing(CommandInfo::getName));
        return Collections.unmodifiableList(infos);
    }

    /**
     * 以原文入口执行一条命令（进程级，无会话）。
     *
     * @param input 用户输入原文（如 {@code "/help"}），可为 {@code null}
     * @return 执行结果，保证非 {@code null}
     */
    public CommandResult execute(String input) {
        return execute(input, null);
    }

    /**
     * 以原文入口执行一条命令。
     * <p>
     * 先做语法解析，再走 {@link #execute(String, CommandArguments, String)} 的同一条分发路径：
     * 两条入口只在「参数从哪来」上不同，别名解析、唯一性判定与异常处置完全一致。
     *
     * @param input     用户输入原文（如 {@code "/agent coder"}），可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     * @return 执行结果，保证非 {@code null}
     */
    public CommandResult execute(String input, String sessionId) {
        ParsedCommand parsed = CommandLineParser.parse(input);
        if (!parsed.isCommand()) {
            return CommandResult.unknown("不是命令：" + input);
        }
        if (parsed.error() != null) {
            return CommandResult.error(parsed.error());
        }
        return dispatch(parsed.name(), parsed.arguments(), sessionId);
    }

    /**
     * 以结构化入口执行一条命令：外壳直接给定命令名（或别名）与参数，跳过切分。
     * <p>
     * 给 Web / TUI 这类「本来就有结构化参数」的调用方用：把参数拼成一行原文再让内核拆回来，
     * 只会白白引入引号与转义的出错面。
     *
     * @param commandName 命令名或别名，不可为空白
     * @param arguments   参数，可为 {@code null}（等价 {@link CommandArguments#EMPTY}）
     * @param sessionId   会话标识，可为 {@code null}
     * @return 执行结果，保证非 {@code null}
     */
    public CommandResult execute(String commandName, CommandArguments arguments, String sessionId) {
        if (commandName == null || commandName.trim().isEmpty()) {
            return CommandResult.unknown("命令名不可为空");
        }
        return dispatch(commandName, arguments == null ? CommandArguments.EMPTY : arguments, sessionId);
    }

    /**
     * 渲染全量命令帮助：按命令名升序，列出用法、说明与别名。
     *
     * @return 帮助文本，保证非 {@code null}
     */
    public String renderHelp() {
        List<CommandInfo> infos;
        try {
            infos = commands();
        } catch (RuntimeException e) {
            // 帮助是外壳的兜底入口，插件注册缺陷不该让它崩
            LOG.warn("读取命令清单失败", e);
            return "命令清单读取失败：" + e.getMessage();
        }
        if (infos.isEmpty()) {
            return "当前没有任何可用命令。";
        }
        StringBuilder text = new StringBuilder();
        text.append("可用命令（").append(infos.size()).append(" 条）：");
        for (CommandInfo info : infos) {
            text.append('\n').append(renderHelpLine(info));
        }
        return text.toString();
    }

    /**
     * 渲染单条命令帮助，命令名或别名都可以查。
     *
     * @param nameOrAlias 命令名或别名，可为 {@code null}
     * @return 帮助文本，保证非 {@code null}
     */
    public String renderHelp(String nameOrAlias) {
        List<CommandInfo> matched;
        try {
            matched = match(nameOrAlias);
        } catch (RuntimeException e) {
            LOG.warn("读取命令清单失败: query={}", nameOrAlias, e);
            return "命令清单读取失败：" + e.getMessage();
        }
        if (matched.isEmpty()) {
            return "未找到命令：" + COMMAND_PREFIX + nameOrAlias + "（输入 /help 查看全部命令）";
        }
        if (matched.size() > 1) {
            return "别名 " + COMMAND_PREFIX + nameOrAlias + " 有歧义，可能是 " + displayNames(matched);
        }
        return renderCommandHelp(matched.get(0));
    }

    /**
     * 共享分发：名字优先、其次别名 → 取唯一处理器 → 调用点线程内联执行。
     *
     * @param name      命令名或别名，不可为空白
     * @param arguments 参数，不可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     * @return 执行结果，保证非 {@code null}
     */
    private CommandResult dispatch(String name, CommandArguments arguments, String sessionId) {
        List<CommandInfo> matched;
        try {
            matched = match(name);
        } catch (RuntimeException e) {
            // 清单读不出来时不能报「未知命令」，否则会把插件注册缺陷伪装成用户拼错
            LOG.warn("读取命令清单失败: command={}", name, e);
            return CommandResult.error("命令清单读取失败：" + e.getMessage());
        }
        if (matched.isEmpty()) {
            return CommandResult.unknown("未知命令：" + COMMAND_PREFIX + name + "（输入 /help 查看可用命令）");
        }
        if (matched.size() > 1) {
            LOG.warn("命令别名有歧义: alias={} candidates={}", name, plainNames(matched));
            return CommandResult.error("别名 " + COMMAND_PREFIX + name + " 有歧义，可能是 " + displayNames(matched));
        }

        String canonical = matched.get(0).getName();
        ExtensionHandler<CommandRequest, CommandResult> handler;
        try {
            // 一条命令一个实现：多命中说明有人在用 contribute 注册类型级命令处理器，当场暴露
            handler = extensions.handler(CommandRequest.class, canonical);
        } catch (ExtensionException e) {
            return resolveHandlerFailure(name, canonical, e);
        }
        CommandRequest request = new CommandRequest(canonical, arguments, sessionId);
        try {
            CommandResult result = extensions.invoke(handler, request);
            return result == null ? CommandResult.ok(null) : result;
        } catch (RuntimeException e) {
            // 同步侧刻意没有护栏，异常处置是调用点（这里）的责任
            LOG.warn("命令执行失败: command={} sessionId={}", canonical, sessionId, e);
            return CommandResult.error("命令执行失败：" + e.getMessage());
        }
    }

    /**
     * 解析查找失败：区分「命令恰好被卸载」与「处理器不唯一」。
     *
     * @param name      原始输入的名字（命令名或别名）
     * @param canonical 命中的命令名
     * @param exception 查找异常
     * @return 失败结果，保证非 {@code null}
     */
    private CommandResult resolveHandlerFailure(String name, String canonical, ExtensionException exception) {
        if (exception.getCode() == ExtensionException.Code.NO_HANDLER) {
            // 清单与注册表之间出现竞态（插件正好在卸载）：对用户而言就是这条命令没了
            return CommandResult.unknown("未知命令：" + COMMAND_PREFIX + name + "（输入 /help 查看可用命令）");
        }
        LOG.warn("命令处理器不唯一: command={}", canonical, exception);
        return CommandResult.error("命令处理器不唯一：" + COMMAND_PREFIX + canonical
                + "（可能有插件用 contribute 注册了类型级命令处理器）");
    }

    /**
     * 按名字匹配命令：命令名优先，其次别名。
     *
     * @param nameOrAlias 命令名或别名，可为 {@code null}
     * @return 命中列表：0 个未命中、1 个唯一命中、多个表示别名有歧义；保证非 {@code null}
     */
    private List<CommandInfo> match(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isEmpty()) {
            return Collections.emptyList();
        }
        List<CommandInfo> infos = commands();
        for (CommandInfo info : infos) {
            if (info.getName().equals(nameOrAlias)) {
                // 命令名优先：与命令名同名的别名因此静默不可达（文档已写明）
                return Collections.singletonList(info);
            }
        }
        List<CommandInfo> matched = new ArrayList<CommandInfo>();
        for (CommandInfo info : infos) {
            if (info.getAliases().contains(nameOrAlias)) {
                matched.add(info);
            }
        }
        return matched;
    }

    /**
     * 渲染帮助列表中的一行。
     *
     * @param info 清单项
     * @return 单行文本
     */
    private static String renderHelpLine(CommandInfo info) {
        StringBuilder line = new StringBuilder("  ").append(padLeft(leftColumn(info)));
        line.append(info.getSummary() == null ? NO_SUMMARY : info.getSummary());
        if (!info.getAliases().isEmpty()) {
            line.append("（别名：").append(displayAliases(info)).append('）');
        }
        return line.toString();
    }

    /**
     * 渲染单条命令的详细帮助。
     *
     * @param info 清单项
     * @return 多行文本
     */
    private static String renderCommandHelp(CommandInfo info) {
        StringBuilder text = new StringBuilder(leftColumn(info));
        text.append("\n  ").append(info.getSummary() == null ? NO_SUMMARY : info.getSummary());
        if (!info.getAliases().isEmpty()) {
            text.append("\n  别名：").append(displayAliases(info));
        }
        return text.toString();
    }

    /**
     * 拼出帮助左列：命令名 + 用法片段。
     *
     * @param info 清单项
     * @return 左列文本（不含缩进）
     */
    private static String leftColumn(CommandInfo info) {
        String usage = info.getUsage();
        return COMMAND_PREFIX + info.getName() + (usage == null || usage.isEmpty() ? "" : " " + usage);
    }

    /**
     * 把左列补齐到固定宽度。
     *
     * @param left 左列文本
     * @return 补齐后的左列（超宽时只补一个空格）
     */
    private static String padLeft(String left) {
        StringBuilder padded = new StringBuilder(left);
        if (padded.length() >= HELP_LEFT_WIDTH) {
            return padded.append(' ').toString();
        }
        while (padded.length() < HELP_LEFT_WIDTH) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * 渲染别名：带前缀、以顿号分隔。
     *
     * @param info 清单项
     * @return 别名文本（不含外层括号）
     */
    private static String displayAliases(CommandInfo info) {
        StringBuilder text = new StringBuilder();
        for (String alias : info.getAliases()) {
            if (text.length() > 0) {
                text.append('、');
            }
            text.append(COMMAND_PREFIX).append(alias);
        }
        return text.toString();
    }

    /**
     * 渲染命令名列表：带前缀、以顿号分隔，供歧义提示使用。
     *
     * @param infos 命中的清单项
     * @return 命令名文本
     */
    private static String displayNames(List<CommandInfo> infos) {
        StringBuilder text = new StringBuilder();
        for (CommandInfo info : infos) {
            if (text.length() > 0) {
                text.append('、');
            }
            text.append(COMMAND_PREFIX).append(info.getName());
        }
        return text.toString();
    }

    /**
     * 渲染命令名列表（不带前缀），供日志使用。
     *
     * @param infos 命中的清单项
     * @return 命令名文本
     */
    private static String plainNames(List<CommandInfo> infos) {
        StringBuilder text = new StringBuilder();
        for (CommandInfo info : infos) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(info.getName());
        }
        return text.toString();
    }
}
