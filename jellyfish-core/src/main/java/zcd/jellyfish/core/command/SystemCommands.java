package zcd.jellyfish.core.command;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.core.compact.CompactionPlan;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.ConfigReloader;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ReloadOutcome;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.PluginReloadReport;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionCompaction;
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
 * <b>不做</b>：{@code /exit} 属外壳职责（进程退出），不进注册表；{@code /todo} 与待办状态归
 * {@code jellyfish-plugin-todo}（插件自持存储，经 {@code PromptContributionRequest} 注入
 * system prompt），内核不再持有该领域。
 * <p>
 * <b>{@code /compact} 只起头、不等结果</b>：它派发给 {@link ConversationCompactor} 后立刻返回，
 * 真正的结果由外壳轮询压缩状态呈现（见 {@code ConversationCompactor.Status}）。在渲染线程上同步等
 * 一次完整的模型调用等于把界面冻住——命令是给界面用的，不能让界面等它。
 *
 * @author zcd
 */
@Singleton
public class SystemCommands {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SystemCommands.class);

    /** 内核系统命令的 owner 标识，与插件 {@code pluginId} 区分开。 */
    public static final String OWNER = "core";

    /** 权限模式取值：计划模式。 */
    private static final String MODE_PLAN = "plan";

    /** 权限模式取值：常规模式。 */
    private static final String MODE_NORMAL = "normal";

    /** {@code /compact} 用法文本。 */
    private static final String COMPACT_USAGE = "用法：/compact [preview]";

    /** {@code /compact} 的预览档位取值：只看不发。 */
    private static final String COMPACT_PREVIEW = "preview";

    /**
     * 压缩不可用时的用户提示。
     * <p>
     * 文案落在命令层而不是压缩器：压缩器那边只负责「不可用」这个事实（抛
     * {@code CompactionUnavailableException}），该对用户说什么、下一步该做什么，是给界面用的措辞。
     */
    private static final String COMPACT_UNAVAILABLE_HINT =
            "压缩不可用：没有插件提供压缩策略。压缩由插件决定（摘要指令与参数），内核只负责执行；"
                    + "安装并启用压缩插件后即可使用（会话历史未受影响）。";

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

    /** 会话压缩器：{@code /compact} 只负责起头，执行与状态由它承担。 */
    private final ConversationCompactor compactor;

    /** 运行时配置门面：读 {@code react} 段的压缩参数用于展示默认档位。 */
    private final RuntimeConfig runtimeConfig;

    /** 配置重载器：{@code /reload} 的执行体。 */
    private final ConfigReloader configReloader;

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
     * @param compactor      会话压缩器
     * @param runtimeConfig  运行时配置门面
     * @param configReloader 配置重载器
     */
    @Inject
    public SystemCommands(ExtensionRegistry extensions, CommandManager commandManager,
                          SessionManager sessionManager, ModelManager modelManager, AgentManager agentManager,
                          EventPublisher events, ConversationCompactor compactor,
                          RuntimeConfig runtimeConfig, ConfigReloader configReloader) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.commandManager = Objects.requireNonNull(commandManager, "commandManager must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.compactor = Objects.requireNonNull(compactor, "compactor must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.configReloader = Objects.requireNonNull(configReloader, "configReloader must not be null");
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
        subscriptions.add(register("compact", new CommandDescriptor("把更早的对话压成摘要",
                "[preview]", null), this::compact));
        subscriptions.add(register("reload", new CommandDescriptor("重新加载配置（模型 / agent / 插件）",
                null, null), this::reload));
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
     * {@code /compact}：把更早的对话压成摘要。
     * <p>
     * <b>只有两个形态</b>：无参按默认档位压一次；{@code preview} 只回报将要发生什么。
     * 刻意不做「保留 5 条 / 全压」这类档位——保留多少是「一次请求长什么样」的一部分，
     * 归 {@code react.compactKeepRecentMessages} 与插件策略管；把同一件事同时开成命令参数与配置项，
     * 只会让「我明明配了 20 条，怎么压成 5 条了」变成一个查不出来的疑问。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult compact(CommandRequest request) {
        Session session = resolveSession(request);
        if (session == null) {
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        if (request.getArguments().size() > 1) {
            return CommandResult.error(COMPACT_USAGE);
        }
        if (!request.getArguments().isEmpty()
                && !COMPACT_PREVIEW.equals(request.getArguments().getTokens().get(0).toLowerCase())) {
            return CommandResult.error(COMPACT_USAGE);
        }
        if (!request.getArguments().isEmpty()) {
            return previewCompaction(session);
        }
        if (!compactor.isAvailable()) {
            // 先判可用性：不可用时 start 也会报错，但那里的措辞面向日志与自动路径，命令层自己说到底
            return CommandResult.error(COMPACT_UNAVAILABLE_HINT);
        }
        try {
            compactor.start(session.getSessionId(), CompactionTrigger.MANUAL);
        } catch (JellyfishException e) {
            // 会话不存在 / 没有足够历史 / 已在压：都是用户当场就该看到的原因，原样回报
            return CommandResult.error("无法压缩：" + e.getMessage());
        }
        return CommandResult.ok("已开始压缩，完成后会提示；期间可以继续对话。");
    }

    /**
     * {@code /compact preview}：只回报将会压多少条，不发起任何模型调用。
     *
     * @param session 会话
     * @return 结果
     */
    private CommandResult previewCompaction(Session session) {
        if (!compactor.isAvailable()) {
            return CommandResult.error(COMPACT_UNAVAILABLE_HINT);
        }
        CompactionPlan plan;
        try {
            plan = compactor.plan(session.getSessionId());
        } catch (JellyfishException e) {
            return CommandResult.error("无法预览压缩：" + e.getMessage());
        }
        StringBuilder text = new StringBuilder(compactState(session));
        if (plan == null) {
            return CommandResult.ok(text.append("\n本次没有可压缩的历史（剩余条数不足或已全部压完）。").toString());
        }
        text.append("\n将压缩 ").append(plan.getCompressedCount()).append(" 条消息，保留最近 ")
                .append(plan.getKeepCount()).append(" 条原文，摘要输入约 ")
                .append(plan.getEstimatedTokens()).append(" token。");
        if (plan.hasDropped()) {
            text.append("\n（其中最早的 ").append(plan.getDroppedCount())
                    .append(" 条超出摘要输入预算，不会进摘要也不再发送——它们的信息本次会真正丢失。）");
        }
        return CommandResult.ok(text.toString());
    }

    /**
     * 渲染当前压缩状态。
     *
     * @param session 会话
     * @return 状态文本
     */
    private String compactState(Session session) {
        if (!compactor.isAvailable()) {
            return "压缩上下文：把更早的对话压成摘要，之后每次请求只带摘要与最近若干条原文"
                    + "（会话历史一条不删，界面与 /resume 不受影响）。"
                    + "\n当前：不可用（没有插件提供压缩策略，压缩由插件决定）。";
        }
        SessionCompaction compaction = session.getCompaction();
        if (compaction == null) {
            return "压缩上下文：把更早的对话压成摘要，之后每次请求只带摘要与最近若干条原文"
                    + "（会话历史一条不删，界面与 /resume 不受影响）。\n当前：未压缩。";
        }
        int covered = session.indexOfMessage(compaction.getBoundaryMessageId()) + 1;
        if (covered <= 0) {
            return "压缩上下文：把更早的对话压成摘要，之后每次请求只带摘要与最近若干条原文"
                    + "（会话历史一条不删，界面与 /resume 不受影响）。"
                    + "\n当前：压缩记录已失效（边界消息不在会话里），下次请求会带上完整历史。";
        }
        StringBuilder text = new StringBuilder("压缩上下文：把更早的对话压成摘要，之后每次请求只带摘要与最近若干条原文"
                + "（会话历史一条不删，界面与 /resume 不受影响）。"
                + "\n当前：已压缩 " + covered + " 条更早的消息（摘要 " + compaction.getSummary().length() + " 字");
        if (compaction.getDroppedMessageCount() > 0) {
            text.append("，其中 ").append(compaction.getDroppedMessageCount()).append(" 条超出摘要预算未收录");
        }
        return text.append("）。").toString();
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
                + "\n  压缩：" + compactionLabel(session)
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
     * {@code /reload}：重新读取并应用全部配置。
     * <p>
     * <b>同步等结果</b>：与 {@code /compact} 不同，配置重载不发起模型调用，只做文件读取与索引重建，
     * 耗时在毫秒级；把「做完没做完」交给用户猜反而不如直接回答。
     * <p>
     * <b>失败原样告知</b>：不做回滚（配置的真相在文件里），错误文本带上原因，让用户知道该看哪个文件。
     *
     * @param request 命令请求
     * @return 结果
     */
    private CommandResult reload(CommandRequest request) {
        ReloadOutcome outcome;
        try {
            outcome = configReloader.reload();
        } catch (RuntimeException e) {
            LOG.error("配置重载失败", e);
            return CommandResult.error("配置重载失败：" + e.getMessage());
        }
        return CommandResult.ok(renderReload(outcome));
    }

    /**
     * 渲染重载结果。
     *
     * @param outcome 重载结果
     * @return 文本
     */
    private static String renderReload(ReloadOutcome outcome) {
        StringBuilder text = new StringBuilder("配置已重载（耗时 ")
                .append(outcome.getDurationMillis()).append(" ms）。");
        PluginReloadReport report = outcome.getPluginReport();
        appendPluginChanges(text, "重启", report.getRestarted());
        appendPluginChanges(text, "启动", report.getStarted());
        appendPluginChanges(text, "停止", report.getStopped());
        appendPluginChanges(text, "失败", report.getFailed());
        if (report.isEmpty()) {
            text.append("\n插件：无变化。");
        }
        if (!report.getFailed().isEmpty()) {
            text.append("\n（启动失败的插件请查看日志：多为配置错误或依赖不满足。）");
        }
        return text.toString();
    }

    /**
     * 追加一类插件变动。
     *
     * @param text    目标
     * @param label   变动类型文案
     * @param plugins 插件标识
     */
    private static void appendPluginChanges(StringBuilder text, String label, List<String> plugins) {
        if (plugins.isEmpty()) {
            return;
        }
        text.append("\n插件").append(label).append("：").append(String.join("、", plugins));
    }

    /**
     * 渲染会话压缩状态的一行说明。
     * <p>
     * <b>为什么要显示它</b>：压缩之后「屏幕上看到的」与「模型收到的」不再一致。不给出口，
     * 「模型为什么忘了刚才说的话」会变成一个从界面查不出来的谜。
     *
     * @param session 会话
     * @return 说明文本
     */
    private String compactionLabel(Session session) {
        if (!compactor.isAvailable()) {
            return "不可用（没有插件提供压缩策略）";
        }
        SessionCompaction compaction = session.getCompaction();
        if (compaction == null) {
            return "未压缩";
        }
        int covered = session.indexOfMessage(compaction.getBoundaryMessageId()) + 1;
        if (covered <= 0) {
            return "压缩记录已失效（边界消息不在会话里，本次按未压缩发送）";
        }
        String label = "已压缩 " + covered + " 条更早消息（摘要 " + compaction.getSummary().length() + " 字"
                + (compaction.getDroppedMessageCount() > 0
                ? "，其中 " + compaction.getDroppedMessageCount() + " 条未收录" : "") + "）";
        return label;
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
