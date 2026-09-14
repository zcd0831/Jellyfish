package zcd.jellyfish.core.command;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.core.ReActLooper;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.session.PendingTodo;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 内核系统命令：在 {@link ReActLooper} 之外，给外壳提供会话 / 模型 / agent / 权限 / 待办的基本操作。
 * <p>
 * <b>为什么落在 core 而不是 infra/command</b>：这些命令要读会话、模型与 agent 域服务，
 * 而命令域（{@code CommandManager}）刻意只依赖 {@code ExtensionRegistry}，保持对外壳与其它域中立。
 * 系统命令是「内核作为扩展点使用者」的样板，落 core 才能合法依赖各域服务。
 * <p>
 * <b>与插件命令同源</b>：都经 {@link ExtensionRegistry#handle} 落同一份注册表，owner 固定为 {@code "core"}；
 * 命令名即路由键，别名与用法随 {@link CommandDescriptor} 一起落表。
 * <p>
 * <b>副作用写回域服务</b>：切模型 / 绑 agent / 建会话都改域服务状态，结果文本只给人看；
 * 外壳需要机器可读状态时执行后读对应域服务（例如 {@link SessionManager#current()}）。
 * <p>
 * <b>不做</b>：{@code /exit} 属外壳职责（进程退出），不进注册表；
 * <ul>
 *     <li>LLM 可写的 {@code todo_write} 工具推迟到后续轮，届时一并闭环 {@code ReadOnlyTools}
 *     的「核心工具只读声明」TODO（计划模式下它必须被判为只读）；</li>
 *     <li>{@code /compact}（摘要式压缩，读法 2）依赖会话持久化与额外模型调用，也留待后续轮。</li>
 * </ul>
 *
 * @author zcd
 */
@Singleton
public class SystemCommands {

    /** 内核系统命令的 owner 标识，与插件 {@code pluginId} 区分开。 */
    public static final String OWNER = "core";

    /** 权限模式取值：计划模式。 */
    private static final String MODE_PLAN = "plan";

    /** 权限模式取值：常规模式。 */
    private static final String MODE_NORMAL = "normal";

    /** 待办子命令：新增。 */
    private static final String TODO_ADD = "add";

    /** 待办子命令：完成。 */
    private static final String TODO_DONE = "done";

    /** 待办子命令：清空。 */
    private static final String TODO_CLEAR = "clear";

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions;

    /** 命令域服务，{@code /help} 直接复用它。 */
    private final CommandManager commandManager;

    /** 会话域服务。 */
    private final SessionManager sessionManager;

    /** 模型门面。 */
    private final ModelManager modelManager;

    /** agent 门面。 */
    private final AgentManager agentManager;

    /** 已注册的命令句柄，{@link #close()} 时回收。 */
    private final List<Subscription> subscriptions = new ArrayList<Subscription>();

    /**
     * 构造系统命令注册器。
     *
     * @param extensions     同步扩展点策略
     * @param commandManager 命令域服务
     * @param sessionManager 会话域服务
     * @param modelManager   模型门面
     * @param agentManager   agent 门面
     */
    @Inject
    public SystemCommands(ExtensionRegistry extensions, CommandManager commandManager,
                          SessionManager sessionManager, ModelManager modelManager, AgentManager agentManager) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.commandManager = Objects.requireNonNull(commandManager, "commandManager must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
    }

    /**
     * 注册全部系统命令。幂等：重复调用不会重复注册。
     */
    public void register() {
        if (!subscriptions.isEmpty()) {
            return;
        }
        subscriptions.add(register("help", new CommandDescriptor("显示命令帮助", "[命令]", aliases("h", "?")),
                this::help));
        subscriptions.add(register("new", new CommandDescriptor("新建会话并切换为当前", null, null), this::newSession));
        subscriptions.add(register("session", new CommandDescriptor("列出全部会话", null, aliases("sessions")),
                this::listSessions));
        subscriptions.add(register("resume", new CommandDescriptor("切换到已有会话", "<sessionId>", null),
                this::resume));
        subscriptions.add(register("model", new CommandDescriptor("查看或切换模型", "[provider/model]", null),
                this::model));
        subscriptions.add(register("agent", new CommandDescriptor("查看或切换 agent", "[agentId]", aliases("a")),
                this::agent));
        subscriptions.add(register("mode", new CommandDescriptor("查看或切换权限模式", "[plan|normal]", null),
                this::mode));
        subscriptions.add(register("status", new CommandDescriptor("显示当前会话概要", null, null), this::status));
        subscriptions.add(register("usage", new CommandDescriptor("显示当前会话 token 用量", null, aliases("cost")),
                this::usage));
        subscriptions.add(register("todo", new CommandDescriptor("查看或管理待办", "[add|done|clear]", null),
                this::todo));
    }

    /**
     * 回收全部系统命令注册。
     */
    public void close() {
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
    }

    /**
     * 注册一条命令。
     *
     * @param name       命令名（路由键）
     * @param descriptor 命令名片
     * @param handler    处理器
     * @return 注册句柄
     */
    private Subscription register(String name, CommandDescriptor descriptor,
                                  ExtensionHandler<CommandRequest, CommandResult> handler) {
        return extensions.handle(OWNER, CommandRequest.class, name, descriptor, handler, RegisterOptions.DEFAULT);
    }

    /**
     * {@code /help [命令]}：直接复用命令域渲染，避免第二份帮助文案。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult help(CommandRequest request) {
        CommandArguments arguments = request.getArguments();
        if (arguments.isEmpty()) {
            return CommandResult.ok(commandManager.renderHelp());
        }
        return CommandResult.ok(commandManager.renderHelp(arguments.getTokens().get(0)));
    }

    /**
     * {@code /new}：新建会话并切换为当前。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult newSession(CommandRequest request) {
        Session session = sessionManager.createDefault();
        sessionManager.switchTo(session.getSessionId());
        return CommandResult.ok("已新建会话：" + session.getSessionId());
    }

    /**
     * {@code /session}：列出全部会话，当前会话带 {@code *} 标记。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult listSessions(CommandRequest request) {
        List<Session> sessions = new ArrayList<Session>(sessionManager.all());
        if (sessions.isEmpty()) {
            return CommandResult.ok("当前没有任何会话，可用 /new 新建。");
        }
        sessions.sort(Comparator.comparingLong(Session::getCreatedAt));
        Session current = sessionManager.current();
        String currentId = current == null ? null : current.getSessionId();
        StringBuilder text = new StringBuilder("会话列表：");
        for (Session session : sessions) {
            text.append('\n').append(session.getSessionId().equals(currentId) ? "* " : "  ")
                    .append(session.getSessionId())
                    .append("（agent=").append(nullToDash(session.getAgentId()))
                    .append("，model=").append(modelLabel(session))
                    .append("，消息=").append(session.size()).append('）');
        }
        return CommandResult.ok(text.toString());
    }

    /**
     * {@code /resume <sessionId>}：切换当前会话。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult resume(CommandRequest request) {
        if (request.getArguments().size() != 1) {
            return CommandResult.error("用法：/resume <sessionId>");
        }
        String sessionId = request.getArguments().getTokens().get(0);
        try {
            sessionManager.switchTo(sessionId);
            return CommandResult.ok("已切换到会话：" + sessionId);
        } catch (JellyfishException e) {
            return CommandResult.error("会话不存在：" + sessionId);
        }
    }

    /**
     * {@code /model [provider/model]}：无参列出可用模型，带参切换当前会话模型。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult model(CommandRequest request) {
        if (request.getArguments().isEmpty()) {
            return CommandResult.ok(renderModels());
        }
        if (request.getArguments().size() != 1) {
            return CommandResult.error("用法：/model [provider/model]");
        }
        String sessionId = sessionIdOf(request);
        if (sessionId == null) {
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        String token = request.getArguments().getTokens().get(0);
        String providerName = null;
        String modelName = token;
        int slash = token.indexOf('/');
        if (slash > 0 && slash < token.length() - 1) {
            providerName = token.substring(0, slash);
            modelName = token.substring(slash + 1);
        }
        try {
            if (providerName == null) {
                providerName = requireProviderForModel(modelName);
            }
            modelManager.resolve(providerName, modelName);
        } catch (JellyfishException e) {
            return CommandResult.error("模型不存在：" + token);
        }
        sessionManager.switchModel(sessionId, providerName, modelName);
        return CommandResult.ok("已切换模型：" + providerName + "/" + modelName);
    }

    /**
     * {@code /agent [agentId]}：无参列出 agent，带参绑定当前会话 agent。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult agent(CommandRequest request) {
        if (request.getArguments().isEmpty()) {
            return CommandResult.ok(renderAgents());
        }
        if (request.getArguments().size() != 1) {
            return CommandResult.error("用法：/agent [agentId]");
        }
        String sessionId = sessionIdOf(request);
        if (sessionId == null) {
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        String agentId = request.getArguments().getTokens().get(0);
        try {
            agentManager.require(agentId);
        } catch (JellyfishException e) {
            return CommandResult.error("agent 不存在：" + agentId);
        }
        sessionManager.bindAgent(sessionId, agentId);
        return CommandResult.ok("已绑定 agent：" + agentId);
    }

    /**
     * {@code /mode [plan|normal]}：查看或切换权限模式。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult mode(CommandRequest request) {
        Session session = resolveSession(request);
        if (session == null) {
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        if (request.getArguments().isEmpty()) {
            return CommandResult.ok("当前权限模式：" + session.getPermissionMode().name().toLowerCase());
        }
        if (request.getArguments().size() != 1) {
            return CommandResult.error("用法：/mode [plan|normal]");
        }
        String mode = request.getArguments().getTokens().get(0).toLowerCase();
        if (MODE_PLAN.equals(mode)) {
            sessionManager.setPermissionMode(session.getSessionId(), PermissionMode.PLAN);
            return CommandResult.ok("已切换到计划模式（仅只读工具可用）。");
        }
        if (MODE_NORMAL.equals(mode)) {
            sessionManager.setPermissionMode(session.getSessionId(), PermissionMode.NORMAL);
            return CommandResult.ok("已切换到常规模式。");
        }
        return CommandResult.error("用法：/mode [plan|normal]");
    }

    /**
     * {@code /status}：显示当前会话概要。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult status(CommandRequest request) {
        Session session = resolveSession(request);
        if (session == null) {
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        return CommandResult.ok("会话：" + session.getSessionId()
                + "\n  model：" + modelLabel(session)
                + "\n  agent：" + nullToDash(session.getAgentId())
                + "\n  权限模式：" + session.getPermissionMode().name().toLowerCase()
                + "\n  消息数：" + session.size()
                + "\n  token：" + session.getUsage().getTotalTokens()
                + "（输入 " + session.getUsage().getPromptTokens()
                + " / 输出 " + session.getUsage().getCompletionTokens() + "）");
    }

    /**
     * {@code /usage}：显示当前会话 token 累计。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult usage(CommandRequest request) {
        Session session = resolveSession(request);
        if (session == null) {
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        return CommandResult.ok("本会话 token 累计：" + session.getUsage().getTotalTokens()
                + "（输入 " + session.getUsage().getPromptTokens()
                + "，输出 " + session.getUsage().getCompletionTokens()
                + "，调用 " + session.getUsage().getLlmCalls() + " 次）");
    }

    /**
     * {@code /todo [add|done|clear]}：查看或管理会话待办。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult todo(CommandRequest request) {
        String sessionId = sessionIdOf(request);
        if (sessionId == null) {
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        List<String> tokens = request.getArguments().getTokens();
        if (tokens.isEmpty()) {
            return CommandResult.ok(renderTodos(sessionManager.todosOf(sessionId)));
        }
        String subCommand = tokens.get(0);
        if (TODO_CLEAR.equals(subCommand)) {
            return CommandResult.ok("已清空 " + sessionManager.clearTodos(sessionId) + " 条待办。");
        }
        if (TODO_ADD.equals(subCommand)) {
            if (tokens.size() < 2) {
                return CommandResult.error("用法：/todo add <内容>");
            }
            String content = StringUtils.join(tokens.subList(1, tokens.size()), ' ');
            PendingTodo added = sessionManager.addTodo(sessionId, content);
            return CommandResult.ok("已添加待办 " + added.getId() + "：" + added.getContent());
        }
        if (TODO_DONE.equals(subCommand)) {
            if (tokens.size() != 2) {
                return CommandResult.error("用法：/todo done <id>");
            }
            String todoId = tokens.get(1);
            if (!sessionManager.completeTodo(sessionId, todoId)) {
                return CommandResult.error("未找到未完成的待办：" + todoId);
            }
            return CommandResult.ok("已完成待办 " + todoId + "。");
        }
        return CommandResult.error("用法：/todo [add <内容>|done <id>|clear]");
    }

    /**
     * 渲染模型清单，当前会话选中项带 {@code *} 标记。
     *
     * @return 文本
     */
    private String renderModels() {
        StringBuilder text = new StringBuilder("可用模型：");
        Session session = sessionManager.current();
        boolean empty = true;
        for (Provider provider : modelManager.getProviders()) {
            for (Model model : provider.getModels()) {
                empty = false;
                boolean selected = session != null && provider.getName().equals(session.getProvider())
                        && model.getName().equals(session.getModel());
                text.append('\n').append(selected ? "* " : "  ")
                        .append(provider.getName()).append('/').append(model.getName());
            }
        }
        return empty ? "当前没有任何可用模型。" : text.toString();
    }

    /**
     * 渲染 agent 清单，默认项与当前会话绑定项带标记。
     *
     * @return 文本
     */
    private String renderAgents() {
        List<AgentDefinition> definitions = new ArrayList<AgentDefinition>(agentManager.all());
        if (definitions.isEmpty()) {
            return "当前没有配置任何 agent。";
        }
        String defaultAgentId = agentManager.getDefaultAgentId();
        Session session = sessionManager.current();
        String boundAgentId = session == null ? null : session.getAgentId();
        StringBuilder text = new StringBuilder("可用 agent：");
        for (AgentDefinition definition : definitions) {
            String agentId = definition.getAgentId();
            text.append('\n').append(agentId.equals(boundAgentId) ? "* " : "  ").append(agentId);
            if (definition.getDescription() != null) {
                text.append("（").append(definition.getDescription()).append('）');
            }
            if (agentId.equals(defaultAgentId)) {
                text.append("[默认]");
            }
        }
        return text.toString();
    }

    /**
     * 渲染待办清单。
     *
     * @param todos 待办列表
     * @return 文本
     */
    private static String renderTodos(List<PendingTodo> todos) {
        if (todos.isEmpty()) {
            return "当前没有待办。";
        }
        StringBuilder text = new StringBuilder("待办：");
        for (PendingTodo todo : todos) {
            text.append('\n').append(todo.isDone() ? "  [x] " : "  [ ] ")
                    .append(todo.getId()).append(". ").append(todo.getContent());
        }
        return text.toString();
    }

    /**
     * 取当前会话标识。
     *
     * @return 当前会话标识，没有当前会话时为 {@code null}
     */
    private String currentSessionId() {
        Session session = sessionManager.current();
        return session == null ? null : session.getSessionId();
    }

    /**
     * 解析命令作用的会话标识：请求带会话标识则用它，否则回退到当前会话。
     *
     * @param request 命令请求
     * @return 会话标识，两者都没有时为 {@code null}
     */
    private String sessionIdOf(CommandRequest request) {
        return StringUtils.isNotBlank(request.getSessionId()) ? request.getSessionId() : currentSessionId();
    }

    /**
     * 解析命令作用的会话运行态。
     *
     * @param request 命令请求
     * @return 会话，没有可作用会话时为 {@code null}
     * @throws JellyfishException 请求携带的会话标识不存在时抛出
     */
    private Session resolveSession(CommandRequest request) {
        String sessionId = sessionIdOf(request);
        return sessionId == null ? null : sessionManager.require(sessionId);
    }

    /**
     * 渲染会话当前模型标签。
     *
     * @param session 会话
     * @return 标签；跟随默认时为 {@code 默认}
     */
    private static String modelLabel(Session session) {
        if (StringUtils.isAnyBlank(session.getProvider(), session.getModel())) {
            return "默认";
        }
        return session.getProvider() + "/" + session.getModel();
    }

    /**
     * 查找提供指定模型名的第一个 provider。
     *
     * @param modelName 模型名
     * @return provider 名
     * @throws JellyfishException 没有任何 provider 提供该模型时抛出
     */
    private String requireProviderForModel(String modelName) {
        for (Provider provider : modelManager.getProviders()) {
            for (Model model : provider.getModels()) {
                if (modelName.equals(model.getName())) {
                    return provider.getName();
                }
            }
        }
        throw new JellyfishException("no provider provides model: " + modelName);
    }

    /**
     * 构造别名列表。
     *
     * @param aliases 别名
     * @return 不可修改列表
     */
    private static List<String> aliases(String... aliases) {
        List<String> list = new ArrayList<String>(aliases.length);
        Collections.addAll(list, aliases);
        return list;
    }

    /**
     * 把 {@code null} 展示为破折号，避免输出里出现字面量 {@code null}。
     *
     * @param value 值
     * @return 展示文本
     */
    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }
}
