package zcd.jellyfish.core.command;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
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
 * 内核系统命令：在 {@link ReActLooper} 之外，给外壳提供会话 / 模型 / agent / 权限的基本操作。
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
 *     <li>{@code /todo} 与待办状态归 {@code jellyfish-plugin-todo}（插件自持存储，经
 *     {@code PromptContributionRequest} 注入 system prompt），内核不再持有该领域；</li>
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

    /** 通知发布入口，用于在切换到无提示词的 agent 时广播配置告警。 */
    private final EventPublisher events;

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
     * @param events         通知发布入口
     */
    @Inject
    public SystemCommands(ExtensionRegistry extensions, CommandManager commandManager,
                          SessionManager sessionManager, ModelManager modelManager, AgentManager agentManager,
                          EventPublisher events) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.commandManager = Objects.requireNonNull(commandManager, "commandManager must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
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
        subscriptions.add(register("delete", new CommandDescriptor("删除会话（含持久化文件）", "<sessionId>",
                aliases("rm")), this::deleteSession));
        // 只读候选查询：与执行处理器平行，外壳「选中命令就弹选择页」时走这条路径，不产生任何副作用
        subscriptions.add(registerOptions("resume", this::resumeOptions));
        subscriptions.add(registerOptions("model", this::modelOptions));
        subscriptions.add(registerOptions("agent", this::agentOptions));
        subscriptions.add(registerOptions("mode", this::modeOptions));
        subscriptions.add(registerOptions("delete", this::deleteOptions));
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
     * 注册一条命令的只读候选处理器。
     * <p>
     * 与执行处理器同路由键、异请求类型，因此互不覆盖；候选处理器没有名片（名片属于命令本身）。
     *
     * @param name    命令名（路由键）
     * @param handler 候选处理器
     * @return 注册句柄
     */
    private Subscription registerOptions(String name, ExtensionHandler<CommandOptionRequest, CommandOptions> handler) {
        return extensions.handle(OWNER, CommandOptionRequest.class, name, null, handler, RegisterOptions.DEFAULT);
    }

    /**
     * {@code /resume} 的只读候选：可切换的会话。
     *
     * @param request 候选查询请求
     * @return 候选结果
     */
    private CommandOptions resumeOptions(CommandOptionRequest request) {
        return CommandOptions.of(sessionChoices(sortedSessions()));
    }

    /**
     * {@code /delete} 的只读候选：可删除的会话。
     *
     * @param request 候选查询请求
     * @return 候选结果
     */
    private CommandOptions deleteOptions(CommandOptionRequest request) {
        return CommandOptions.of(sessionChoices(sortedSessions()));
    }

    /**
     * {@code /model} 的只读候选：可用模型。
     *
     * @param request 候选查询请求
     * @return 候选结果
     */
    private CommandOptions modelOptions(CommandOptionRequest request) {
        return CommandOptions.of(modelChoices());
    }

    /**
     * {@code /agent} 的只读候选：可绑定的 agent。
     *
     * @param request 候选查询请求
     * @return 候选结果
     */
    private CommandOptions agentOptions(CommandOptionRequest request) {
        return CommandOptions.of(agentChoices());
    }

    /**
     * {@code /mode} 的只读候选：两种权限模式。
     *
     * @param request 候选查询请求
     * @return 候选结果；没有当前会话时为空
     */
    private CommandOptions modeOptions(CommandOptionRequest request) {
        Session session = sessionManager.current();
        return session == null ? CommandOptions.empty() : CommandOptions.of(modeChoices(session.getPermissionMode()));
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
        List<Session> sessions = sortedSessions();
        return CommandResult.ok(renderSessions(sessions));
    }

    /**
     * 取全部会话并按创建时间升序排列。
     *
     * @return 排序后的会话列表
     */
    private List<Session> sortedSessions() {
        List<Session> sessions = new ArrayList<Session>(sessionManager.all());
        sessions.sort(Comparator.comparingLong(Session::getCreatedAt));
        return sessions;
    }

    /**
     * 渲染会话清单，当前会话带 {@code *} 标记。
     *
     * @param sessions 已排序的会话列表
     * @return 文本
     */
    private String renderSessions(List<Session> sessions) {
        if (sessions.isEmpty()) {
            return "当前没有任何会话，可用 /new 新建。";
        }
        String currentId = currentSessionId();
        StringBuilder text = new StringBuilder("会话列表：");
        for (Session session : sessions) {
            text.append('\n').append(session.getSessionId().equals(currentId) ? "* " : "  ")
                    .append(session.getSessionId())
                    .append("（agent=").append(nullToDash(session.getAgentId()))
                    .append("，model=").append(modelLabel(session))
                    .append("，消息=").append(session.size()).append('）');
        }
        return text.toString();
    }

    /**
     * 构造会话候选：选中后追加为 {@code /resume <sessionId>} 的参数。
     *
     * @param sessions 已排序的会话列表
     * @return 候选清单
     */
    private List<CommandChoice> sessionChoices(List<Session> sessions) {
        String currentId = currentSessionId();
        List<CommandChoice> choices = new ArrayList<CommandChoice>(sessions.size());
        for (Session session : sessions) {
            String sessionId = session.getSessionId();
            String description = "agent=" + nullToDash(session.getAgentId())
                    + "，model=" + modelLabel(session)
                    + "，消息=" + session.size();
            choices.add(new CommandChoice(sessionId, sessionId, description, sessionId.equals(currentId)));
        }
        return choices;
    }

    /**
     * {@code /delete <sessionId>}：删除会话（含插件存储）。
     * <p>
     * 无参时回退为候选清单：删除是破坏性操作，让外壳弹选择页比让人手敲一长串 UUID 更不容易出错。
     * 删除当前会话是允许的——{@link SessionManager#delete(String)} 会清掉当前指针，
     * 外壳据此回到无会话状态（TUI 首页）。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult deleteSession(CommandRequest request) {
        if (request.getArguments().isEmpty()) {
            List<Session> sessions = sortedSessions();
            return CommandResult.choices(renderSessions(sessions), sessionChoices(sessions));
        }
        if (request.getArguments().size() != 1) {
            return CommandResult.error("用法：/delete <sessionId>");
        }
        String sessionId = request.getArguments().getTokens().get(0);
        try {
            Session deleted = sessionManager.delete(sessionId);
            if (deleted == null) {
                return CommandResult.error("会话不存在：" + sessionId);
            }
            return CommandResult.ok("已删除会话：" + sessionId);
        } catch (JellyfishException e) {
            // 插件删不掉时会话仍在内存里，如实告知失败比默默假装删掉更安全
            return CommandResult.error("删除会话失败：" + e.getMessage());
        }
    }

    /**
     * {@code /resume <sessionId>}：切换当前会话。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult resume(CommandRequest request) {
        if (request.getArguments().isEmpty()) {
            List<Session> sessions = sortedSessions();
            return CommandResult.choices(renderSessions(sessions), sessionChoices(sessions));
        }
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
            return CommandResult.choices(renderModels(), modelChoices());
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
            return CommandResult.choices(renderAgents(), agentChoices());
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
        return CommandResult.ok(boundAgentMessage(agentId));
    }

    /**
     * 拼出绑定成功的提示文本，并在该 agent 没有提示词时追加告警。
     * <p>
     * 提示词来自与 {@code agents.json} 同目录的 {@code {agentId}.md}：配置文件是全局级与项目级两处，
     * 用户很容只写了 JSON 而忘了那个 Markdown 文件。这里不阻止切换（agent 的权限配置仍然生效），
     * 只把「这个 agent 没有系统提示词」这件事当场说清楚——否则用户会以为切换没生效。
     *
     * @param agentId 已绑定的 agent 标识
     * @return 结果文本
     */
    private String boundAgentMessage(String agentId) {
        StringBuilder message = new StringBuilder("已绑定 agent：").append(agentId);
        if (StringUtils.isBlank(agentManager.systemPromptOf(agentId))) {
            String warning = "agent [" + agentId + "] 未找到同名 .md 提示词文件，该 agent 没有系统提示词";
            events.publish(new ConfigWarningEvent(agentId, warning));
            message.append('\n').append("提示：").append(warning);
        }
        return message.toString();
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
            return CommandResult.choices("当前权限模式：" + session.getPermissionMode().name().toLowerCase(),
                    modeChoices(session.getPermissionMode()));
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
     * 构造模型候选：选中后追加为 {@code /model <provider>/<model>} 的参数。
     *
     * @return 候选清单
     */
    private List<CommandChoice> modelChoices() {
        Session session = sessionManager.current();
        List<CommandChoice> choices = new ArrayList<CommandChoice>();
        for (Provider provider : modelManager.getProviders()) {
            for (Model model : provider.getModels()) {
                String value = provider.getName() + "/" + model.getName();
                boolean selected = session != null && provider.getName().equals(session.getProvider())
                        && model.getName().equals(session.getModel());
                choices.add(new CommandChoice(value, value, null, selected));
            }
        }
        return choices;
    }

    /**
     * 构造 agent 候选：选中后追加为 {@code /agent <agentId>} 的参数。
     *
     * @return 候选清单
     */
    private List<CommandChoice> agentChoices() {
        Session session = sessionManager.current();
        String boundAgentId = session == null ? null : session.getAgentId();
        String defaultAgentId = agentManager.getDefaultAgentId();
        List<CommandChoice> choices = new ArrayList<CommandChoice>();
        for (AgentDefinition definition : agentManager.all()) {
            String agentId = definition.getAgentId();
            choices.add(new CommandChoice(agentId, agentId, agentDescription(definition, defaultAgentId),
                    agentId.equals(boundAgentId)));
        }
        return choices;
    }

    /**
     * 拼出 agent 候选的补充说明：描述与「默认」标记合并成一行。
     *
     * @param definition    agent 定义
     * @param defaultAgentId 默认 agent 标识，可为 {@code null}
     * @return 说明文本；两者都为空时返回 {@code null}
     */
    private static String agentDescription(AgentDefinition definition, String defaultAgentId) {
        StringBuilder description = new StringBuilder();
        if (definition.getDescription() != null) {
            description.append(definition.getDescription());
        }
        if (definition.getAgentId().equals(defaultAgentId)) {
            if (description.length() > 0) {
                description.append('，');
            }
            description.append("默认");
        }
        return description.length() == 0 ? null : description.toString();
    }

    /**
     * 构造权限模式候选：选中后追加为 {@code /mode <plan|normal>} 的参数。
     *
     * @param mode 当前权限模式
     * @return 候选清单
     */
    private static List<CommandChoice> modeChoices(PermissionMode mode) {
        List<CommandChoice> choices = new ArrayList<CommandChoice>(2);
        choices.add(new CommandChoice(MODE_PLAN, MODE_PLAN, "仅只读工具可用", mode == PermissionMode.PLAN));
        choices.add(new CommandChoice(MODE_NORMAL, MODE_NORMAL, "常规模式（可写）", mode == PermissionMode.NORMAL));
        return choices;
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
