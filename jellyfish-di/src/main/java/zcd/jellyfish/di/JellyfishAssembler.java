package zcd.jellyfish.di;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.subagent.SubAgentPort;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActLooper;
import zcd.jellyfish.core.action.ActionDispatcher;
import zcd.jellyfish.core.command.SystemCommands;
import zcd.jellyfish.core.compact.CompactionHealthIndicator;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.core.input.InputTransforms;
import zcd.jellyfish.core.prompt.CacheBreakWatcher;
import zcd.jellyfish.core.prompt.CacheKeepAlive;
import zcd.jellyfish.core.prompt.PromptAssembler;
import zcd.jellyfish.core.prompt.ToolCatalog;
import zcd.jellyfish.core.prompt.ToolResultAger;
import zcd.jellyfish.core.runtime.AgentRuntime;
import zcd.jellyfish.core.runtime.RunContextHolder;
import zcd.jellyfish.core.runtime.RunEventBus;
import zcd.jellyfish.core.runtime.RunObservationBridge;
import zcd.jellyfish.core.runtime.RunRegistry;
import zcd.jellyfish.core.runtime.RunScheduler;
import zcd.jellyfish.core.subagent.SubAgentArchive;
import zcd.jellyfish.core.subagent.SubAgentDelegationAdapter;
import zcd.jellyfish.core.subagent.SubAgentLauncher;
import zcd.jellyfish.core.subagent.SubAgentPanel;
import zcd.jellyfish.core.subagent.SubAgentTools;
import zcd.jellyfish.core.subagent.TaskTool;
import zcd.jellyfish.core.tool.ToolExecutor;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.agent.AgentRegistry;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.AgentPromptLoader;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.BuiltinAgentLoader;
import zcd.jellyfish.infra.config.ConfigLoader;
import zcd.jellyfish.infra.config.ConfigReloader;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SettingsBinder;
import zcd.jellyfish.infra.config.SettingsReader;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.ClaudeLlmClient;
import zcd.jellyfish.infra.llm.DeepSeekLlmClient;
import zcd.jellyfish.infra.llm.GeminiLlmClient;
import zcd.jellyfish.infra.llm.LlmClientCreator;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.llm.MiniMaxLlmClient;
import zcd.jellyfish.infra.llm.OpenAiLlmClient;
import zcd.jellyfish.infra.metrics.EventChannelHealthIndicator;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.metrics.HealthIndicator;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.metrics.MetricsSubscriber;
import zcd.jellyfish.infra.metrics.ModelHealthIndicator;
import zcd.jellyfish.infra.metrics.PluginHealthIndicator;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ModelRegistry;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.permission.PermissionPolicyProvider;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.shell.ShellIngress;
import zcd.jellyfish.infra.support.ProviderTypes;
import zcd.jellyfish.infra.tooloutput.ToolOutputLimiter;
import zcd.jellyfish.infra.tooloutput.ToolOutputStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 内核的纯 Java 装配工厂：不使用 Dagger，手工把 {@code infra} / {@code core} 的实现装成一张完整的对象图。
 * <p>
 * <b>它为什么存在</b>：装配知识（谁依赖谁、哪些实例必须共用）此前只写在 Dagger 的 {@code @Module} 里，
 * 而 Dagger 的产物是注解处理器生成的类。任何不打算用 Dagger 的外壳（例如 Spring Boot starter）
 * 就只有两条路：要么被迫依赖 Dagger 代码生成，要么把这份知识再抄一遍。<b>两条都不好</b>——
 * 前者把 DI 框架塞进别人的技术栈，后者让「同一实例」这类约束出现第二个落点，
 * 而它们一旦分叉，现场表现是「配置告警发进了没人订阅的通道」这种极难排查的静默故障。
 * <p>
 * <b>与 {@link JellyfishComponent} 的关系</b>：两者是同一张图的两种装法，交付同一个
 * {@link JellyfishRuntime} 契约。{@code @Module} 里的每一条 {@code @Provides} 在这里都有一行对应物，
 * 因此内核新增绑定时<b>两处都要改</b>——这是本方案已知的代价，用
 * {@code JellyfishAssemblerTest} 的「共享实例」断言把分叉风险压到可发现。
 * <p>
 * <b>为什么是全量急切构造，而不是等到第一次访问</b>：Dagger 的组件是惰性的（第一次访问访问器时
 * 才建对象），而这里一次建完。两者的时间点都在 {@code AgentHarness.bootstrap()} 之前，
 * 而内核的纪律是「构造期只拿快照、{@code refresh()} 之后才读值」（例如
 * {@code RunScheduler} 在构造器里读 {@code subAgent.maxConcurrentRuns} 决定线程池大小），
 * 因此急切构造与惰性构造语义等价；换来的好处是「装配期出错立刻暴露」，而不是等到某次调用。
 * <p>
 * 无状态、线程安全：{@link #create(AppConfig)} 每次返回一张独立的对象图。
 *
 * @author zcd
 */
public final class JellyfishAssembler {

    /** 空闲连接数上限，与 Dagger 侧的 {@code LlmModule} 保持一致。 */
    private static final int MAX_IDLE_CONNECTIONS = 10;

    /** 空闲连接保活时间（分钟）。 */
    private static final long KEEP_ALIVE_MINUTES = 5L;

    /** 流式线程池的线程数上限，即并发流式请求上限。 */
    private static final int STREAM_MAX_THREADS = 32;

    /** 流式线程池等待队列容量，队列满后拒绝任务并让调用方拿到异常。 */
    private static final int STREAM_QUEUE_CAPACITY = 128;

    /** 流式线程空闲回收时间（秒）。 */
    private static final long STREAM_KEEP_ALIVE_SECONDS = 60L;

    /** 流式线程名。 */
    private static final String STREAM_THREAD_NAME = "llm-stream";

    private JellyfishAssembler() {
    }

    /**
     * 装配出一张完整的对象图。
     * <p>
     * 唯一的入参是 {@link AppConfig}：它是「去哪读配置」这一部署事实的载体，
     * 其余一切（模型 / agent / 运行期设置 / 插件扫描目录）都由内核在
     * {@code bootstrap()} 里按它去读——与 CLI 外壳走的是同一条路，因此没有任何配置语义被绕过。
     *
     * @param appConfig 应用级配置，不可为 {@code null}
     * @return 装配结果，保证非 {@code null}
     */
    public static JellyfishRuntime create(AppConfig appConfig) {
        Objects.requireNonNull(appConfig, "appConfig must not be null");
        Assembly assembly = new Assembly();
        assembly.assemble(appConfig);
        return assembly;
    }

    /**
     * 对象图的持有者与装配过程：字段在 {@link #assemble} 里一次性写完，此后只读。
     * <p>
     * 用一个「可变字段的私有类 + 一次性装配」而不是「二十个局部变量 + 二十参构造器」，
     * 是为了让装配顺序在代码里自上而下可读——顺序本身是这张图的硬约束（见 {@link #assemble}）。
     * 单线程装配、装配完成即不再写，因此对使用者是事实只读。
     *
     * @author zcd
     */
    private static final class Assembly implements JellyfishRuntime {

        private LlmClientFactory llmClientFactory;
        private ModelManager modelManager;
        private RuntimeConfig runtimeConfig;
        private AgentHarness agentHarness;
        private AgentManager agentManager;
        private PermissionManager permissionManager;
        private ApprovalChannel approvalChannel;
        private ConversationCompactor conversationCompactor;
        private InputDirectives inputDirectives;
        private CommandManager commandManager;
        private SessionManager sessionManager;
        private SessionDefaults sessionDefaults;
        private ExtensionRegistry extensionRegistry;
        private EventChannel eventChannel;
        private RuntimeInfoHolder runtimeInfoHolder;
        private ConversationService conversationService;
        private TurnRegistry turnRegistry;
        private ShellStreams shellStreams;
        private RunEventBus runEventBus;
        private HealthCheck healthCheck;

        /**
         * 按依赖顺序装配整张图。
         * <p>
         * 顺序只受一件事支配：<b>被依赖者先建</b>。有两处不是直觉顺序，都是有原因的：
         * <ol>
         *     <li><b>插件运行时排在 ReAct / 压缩链之后</b>——{@code PluginContextFactory} 需要
         *     {@code SubAgentPort}，而它的实现链是「委派适配器 → 子代理启动器 → ReAct 循环器 →
         *     动作分发器 → 压缩器」。先建插件运行时会拿不到这条路；</li>
         *     <li><b>{@code PluginRuntimeConfig} 只建一次、且此处不刷新有效值</b>——它是
         *     「引用稳定、快照可换」的发布点，真正的刷新在 {@code AgentHarness.bootstrap()} 里
         *     （必须在插件启动之前）。这里若 new 一个新对象刷新，管理器手里那份会永远停在旧快照。</li>
         * </ol>
         *
         * @param appConfig 应用级配置，不可为 {@code null}
         */
        private void assemble(AppConfig appConfig) {
            // 第一层：扩展层。两份策略共用同一份注册表——这是硬约束，不得各建一份
            TypeRegistry registry = new TypeRegistry();
            extensionRegistry = new ExtensionRegistry(registry);
            EventChannelOptions channelOptions = EventChannelOptions.defaults();
            eventChannel = new EventChannel(channelOptions, registry);
            // 窄接口视图与通道是同一个对象：配置 / 模型 / agent 的告警都从这里出去，
            // 只有 bootstrap() 启动的那一个通道才有人订阅
            EventPublisher publisher = eventChannel;

            // 第二层：配置。构造期不读文件，真正的装载发生在 runtimeConfig.refresh()
            SettingsReader settingsReader = new SettingsReader();
            SettingsBinder settingsBinder = new SettingsBinder();
            ConfigLoader configLoader = new ConfigLoader(settingsReader, settingsBinder);
            AgentPromptLoader promptLoader = new AgentPromptLoader(settingsReader);
            BuiltinAgentLoader builtinAgentLoader = new BuiltinAgentLoader(configLoader, promptLoader);
            runtimeConfig = new RuntimeConfig(appConfig, configLoader, publisher, builtinAgentLoader, promptLoader);

            // 第三层：模型与 agent 索引
            ModelRegistry modelRegistry = new ModelRegistry();
            OkHttpClient httpClient = okHttpClient();
            ExecutorService streamExecutor = streamExecutor();
            llmClientFactory = new LlmClientFactory(llmClientCreators(httpClient, streamExecutor), extensionRegistry,
                    streamExecutor);
            modelManager = new ModelManager(runtimeConfig, modelRegistry, llmClientFactory, publisher,
                    extensionRegistry);
            AgentRegistry agentRegistry = new AgentRegistry(publisher);
            agentManager = new AgentManager(runtimeConfig, agentRegistry, publisher);
            // 会话 → 模型的唯一解释器：会话上没写模型时的回落链都在它里面
            SessionModelResolver modelResolver = new SessionModelResolver(modelManager, agentManager);

            // 第四层：会话与命令
            sessionDefaults = new SessionDefaults();
            sessionManager = new SessionManager(agentManager, publisher, extensionRegistry, sessionDefaults);
            commandManager = new CommandManager(extensionRegistry, publisher);

            // 第五层：权限与审批。策略来源就是 AgentManager 本身（不能换一个适配器，
            // 否则「未命中即 fail-open」会出现第二个落点）
            approvalChannel = new ApprovalChannel();
            PermissionPolicyProvider policies = agentManager;
            permissionManager = new PermissionManager(policies, extensionRegistry, publisher, approvalChannel,
                    runtimeConfig);

            // 第六层：可观测性与共享设施
            MetricsRegistry metricsRegistry = new MetricsRegistry();
            MetricsSubscriber metricsSubscriber = new MetricsSubscriber(metricsRegistry, eventChannel);
            runtimeInfoHolder = new RuntimeInfoHolder();
            ActionQueue actionQueue = new ActionQueue();
            ShellIngress shellIngress = new ShellIngress(metricsRegistry);
            ToolOutputStore toolOutputStore = new ToolOutputStore(runtimeConfig);
            ToolOutputLimiter toolOutputLimiter = new ToolOutputLimiter(runtimeConfig, toolOutputStore);

            // 第七层：run 运行时（子代理的账本、许可与事件）
            RunContextHolder runContextHolder = new RunContextHolder();
            RunRegistry runRegistry = new RunRegistry();
            runEventBus = new RunEventBus();
            RunScheduler runScheduler = new RunScheduler(runContextHolder, runRegistry, runEventBus, runtimeConfig);
            AgentRuntime agentRuntime = new AgentRuntime(runRegistry, runContextHolder, runScheduler);
            RunObservationBridge runObservation = new RunObservationBridge(runEventBus, publisher);

            // 第八层：提示词组装链。工具目录与执行器必须看同一份注册表，否则「列出来的工具」与
            // 「能路由到的工具」会是两份
            ToolCatalog toolCatalog = new ToolCatalog(extensionRegistry);
            CacheBreakWatcher cacheBreakWatcher = new CacheBreakWatcher(publisher);
            ToolResultAger toolResultAger = new ToolResultAger(runtimeConfig, extensionRegistry);
            PromptAssembler promptAssembler = new PromptAssembler(agentManager, toolCatalog, runtimeConfig,
                    extensionRegistry, toolResultAger, cacheBreakWatcher);

            // 第九层：压缩（压缩是插件能力、内核只提供机制，因此这里只装机制）
            conversationCompactor = new ConversationCompactor(sessionManager, modelManager, runtimeConfig,
                    extensionRegistry, publisher, modelResolver, promptAssembler);

            // 第十层：动作与工具执行
            ActionDispatcher actionDispatcher = new ActionDispatcher(actionQueue, sessionManager,
                    conversationCompactor, toolCatalog);
            ToolExecutor toolExecutor = new ToolExecutor(permissionManager, extensionRegistry, publisher,
                    toolOutputLimiter, runContextHolder);

            // 第十一层：外壳通道与在途回合表
            turnRegistry = new TurnRegistry(publisher);
            shellStreams = new ShellStreams(shellIngress);

            // 第十二层：输入指令与 ReAct 循环
            InputTransforms inputTransforms = new InputTransforms(extensionRegistry);
            inputDirectives = new InputDirectives(extensionRegistry, toolExecutor, sessionManager);
            ReActLooper reActLooper = new ReActLooper(sessionManager, modelManager, toolExecutor, publisher,
                    promptAssembler, runtimeConfig, conversationCompactor, runContextHolder, modelResolver,
                    extensionRegistry, actionDispatcher);

            // 第十三层：子代理。启动器是唯一入口，「task 工具」与「插件委派」都走它，
            // 这样两边的准入 / 并发 / 深度 / 预算 / 归档行为逐字段一致
            SubAgentArchive subAgentArchive = new SubAgentArchive(toolOutputStore, runtimeConfig);
            SubAgentLauncher subAgentLauncher = new SubAgentLauncher(sessionManager, agentManager, modelResolver,
                    runtimeConfig, reActLooper, runContextHolder, permissionManager, agentRuntime, subAgentArchive);
            TaskTool taskTool = new TaskTool(subAgentLauncher);
            SubAgentPanel subAgentPanel = new SubAgentPanel(agentRuntime);
            SubAgentPort delegations = new SubAgentDelegationAdapter(subAgentLauncher);

            // 第十四层：插件运行时（必须排在 ReAct / 压缩链之后，因为它要拿到委派端口）
            PluginRuntimeConfig pluginRuntimeConfig = PluginRuntimeConfig.defaults();
            pluginRuntimeConfig.refresh(runtimeConfig.getPluginRoots(), runtimeConfig.getPluginsSettings());
            PluginContextFactory pluginContextFactory = new PluginContextFactory(extensionRegistry, eventChannel,
                    registry, runtimeInfoHolder, actionQueue, sessionManager, shellIngress, delegations);
            PF4JPluginManager pluginManager = new PF4JPluginManager(pluginContextFactory, pluginRuntimeConfig,
                    eventChannel);

            // 第十五层：命令、健康检查、组装门面
            ConfigReloader configReloader = new ConfigReloader(runtimeConfig, modelManager, agentManager,
                    pluginRuntimeConfig, pluginManager, publisher);
            SubAgentTools subAgentTools = new SubAgentTools(extensionRegistry, eventChannel, agentManager,
                    runtimeConfig, taskTool, subAgentPanel);
            SystemCommands systemCommands = new SystemCommands(extensionRegistry, commandManager, sessionManager,
                    modelManager, agentManager, publisher, conversationCompactor, runtimeConfig, configReloader,
                    sessionDefaults);
            healthCheck = healthCheck(modelManager, pluginManager, eventChannel, conversationCompactor);
            CacheKeepAlive cacheKeepAlive = new CacheKeepAlive(sessionManager, modelResolver, modelManager,
                    promptAssembler, runtimeConfig);
            agentHarness = new AgentHarness(runtimeConfig, eventChannel, modelManager, agentManager,
                    pluginRuntimeConfig, pluginManager, reActLooper, systemCommands, subAgentTools, sessionManager,
                    conversationCompactor, inputDirectives, cacheKeepAlive, metricsSubscriber, metricsRegistry,
                    runObservation, healthCheck);
            conversationService = new ConversationService(commandManager, inputTransforms, inputDirectives,
                    sessionManager, turnRegistry, shellStreams, agentHarness);
        }

        /**
         * 构造全局共享的 HTTP 客户端：所有 LLM 客户端共用同一个连接池。
         *
         * @return HTTP 客户端，保证非 {@code null}
         */
        private static OkHttpClient okHttpClient() {
            return new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                    .build();
        }

        /**
         * 构造流式调用线程池。
         * <p>
         * 有界而非无界：流式调用是长连接突发型负载，无界缓存线程池会无限创建线程。
         * 核心与最大线程数相等且允许空闲回收，空闲时线程自动消亡、不阻止 JVM 退出。
         *
         * @return 流式线程池，保证非 {@code null}
         */
        private static ExecutorService streamExecutor() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(STREAM_MAX_THREADS, STREAM_MAX_THREADS,
                    STREAM_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(STREAM_QUEUE_CAPACITY),
                    runnable -> {
                        Thread thread = new Thread(runnable, STREAM_THREAD_NAME);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }

        /**
         * 构造厂商客户端创建器表。
         * <p>
         * <b>键一个都不能少</b>：{@code google} / {@code anthropic} 是 {@code gemini} / {@code claude}
         * 的别名键，漏掉它们不会编译失败，而是「配了那个 provider 却静默不可用」。
         *
         * @param httpClient     HTTP 客户端
         * @param streamExecutor 流式线程池
         * @return 不可变映射，保证非 {@code null}
         */
        private static Map<String, LlmClientCreator> llmClientCreators(OkHttpClient httpClient,
                                                                       ExecutorService streamExecutor) {
            Map<String, LlmClientCreator> creators = new LinkedHashMap<String, LlmClientCreator>();
            creators.put(ProviderTypes.OPENAI, provider -> new OpenAiLlmClient(provider, httpClient, streamExecutor));
            creators.put(ProviderTypes.DEEPSEEK,
                    provider -> new DeepSeekLlmClient(provider, httpClient, streamExecutor));
            creators.put(ProviderTypes.MINIMAX,
                    provider -> new MiniMaxLlmClient(provider, httpClient, streamExecutor));
            creators.put(ProviderTypes.GEMINI, provider -> new GeminiLlmClient(provider, httpClient, streamExecutor));
            creators.put(ProviderTypes.GOOGLE, provider -> new GeminiLlmClient(provider, httpClient, streamExecutor));
            creators.put(ProviderTypes.CLAUDE, provider -> new ClaudeLlmClient(provider, httpClient, streamExecutor));
            creators.put(ProviderTypes.ANTHROPIC,
                    provider -> new ClaudeLlmClient(provider, httpClient, streamExecutor));
            return creators;
        }

        /**
         * 拼装健康检查：顺序固定为「模型 → 插件 → 事件通道 → 压缩」。
         * <p>
         * 检查项跨 {@code infra} 与 {@code core} 两层，只能在装配处显式拼装；
         * 顺序固定是为了让报告每次读起来是同一形状。
         *
         * @param modelManager    模型门面
         * @param pluginManager   插件运行时门面
         * @param events          事件通道
         * @param compactor       会话压缩器
         * @return 健康检查，保证非 {@code null}
         */
        private static HealthCheck healthCheck(ModelManager modelManager, PF4JPluginManager pluginManager,
                                              EventChannel events, ConversationCompactor compactor) {
            List<HealthIndicator> indicators = new ArrayList<HealthIndicator>();
            indicators.add(new ModelHealthIndicator(modelManager));
            indicators.add(new PluginHealthIndicator(pluginManager));
            indicators.add(new EventChannelHealthIndicator(events));
            indicators.add(new CompactionHealthIndicator(compactor));
            return new HealthCheck(indicators);
        }

        @Override
        public LlmClientFactory llmClientFactory() {
            return llmClientFactory;
        }

        @Override
        public ModelManager modelManager() {
            return modelManager;
        }

        @Override
        public RuntimeConfig runtimeConfig() {
            return runtimeConfig;
        }

        @Override
        public AgentHarness agentHarness() {
            return agentHarness;
        }

        @Override
        public AgentManager agentManager() {
            return agentManager;
        }

        @Override
        public PermissionManager permissionManager() {
            return permissionManager;
        }

        @Override
        public ApprovalChannel approvalChannel() {
            return approvalChannel;
        }

        @Override
        public ConversationCompactor conversationCompactor() {
            return conversationCompactor;
        }

        @Override
        public InputDirectives inputDirectives() {
            return inputDirectives;
        }

        @Override
        public CommandManager commandManager() {
            return commandManager;
        }

        @Override
        public SessionManager sessionManager() {
            return sessionManager;
        }

        @Override
        public SessionDefaults sessionDefaults() {
            return sessionDefaults;
        }

        @Override
        public ExtensionRegistry extensionRegistry() {
            return extensionRegistry;
        }

        @Override
        public EventChannel eventChannel() {
            return eventChannel;
        }

        @Override
        public RuntimeInfoHolder runtimeInfoHolder() {
            return runtimeInfoHolder;
        }

        @Override
        public ConversationService conversationService() {
            return conversationService;
        }

        @Override
        public TurnRegistry turnRegistry() {
            return turnRegistry;
        }

        @Override
        public ShellStreams shellStreams() {
            return shellStreams;
        }

        @Override
        public RunEventBus runEventBus() {
            return runEventBus;
        }

        @Override
        public HealthCheck healthCheck() {
            return healthCheck;
        }
    }
}
