package zcd.jellyfish.infra.config;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.support.HomePaths;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 运行时配置门面：统一负责所有配置文件的「全局级 + 项目级」双源读取与合并。
 * <p>
 * 三类配置段各对应一份文件与一个根类：{@code models.json} → {@link ModelSettings}、
 * {@code agents.json} → {@link AgentSettings}、{@code jellyfish.json} → {@link JellyfishSettings}；
 * 文件路径全部由 {@code classpath:config.json}（{@link AppConfig}）声明。
 * <p>
 * 合并规则（对每个配置段一致）：
 * <ul>
 *     <li><b>项目级只在被信任时参与合并</b>：它按当前目录读取，因此内容取决于「在哪个仓库里启动」，
 *     而它能改 provider 的 {@code baseUrl}/{@code apiKey}、新增 agent、改落盘目录。未被显式信任过的
 *     项目级文件一律跳过并告警，判据见 {@link ProjectConfigTrust}；</li>
 *     <li>同名 provider 以项目级<b>整对象</b>替换全局级，避免同一 provider 的字段散落在两份文件中；</li>
 *     <li>默认 provider / model 以项目级非空值覆盖全局级，项目级未配置时回退全局级。</li>
 * </ul>
 * 此外它还承载一份<b>不来自任一配置文件片段</b>的派生值：插件扫描根目录。它写在
 * {@code classpath:config.json}（{@link AppConfig} 的 {@link PluginPaths} 段）而不是
 * {@code jellyfish.json}，因为「去哪找插件 jar」与「去哪个文件读配置」属于同一类部署事实。
 * <p>
 * 配置内容在 {@link #refresh()} 时整体重建，并通过单个 {@code volatile} 字段一次性发布
 * {@link RuntimeSnapshot}，因此读取方看到的所有值必然来自同一份快照，不存在「新 provider + 旧默认值」的中间态。
 * <p>
 * 新增配置段时：声明字段 → 在 {@link #refresh()} 中用 {@link #load} 读取并合并 → 放入 {@link RuntimeSnapshot}，
 * 复用同一套双源机制。配置好坏的判定只发 {@link ConfigWarningEvent}、不抛错，启动期缺配置不会直接失败。
 * <p>
 * 依赖的是窄接口 {@link EventPublisher}，不感知具体事件总线实现。
 * <p>
 * <b>构造器不读取配置</b>：构造时只注入依赖，真正加载由装配根在 {@code AgentHarness.bootstrap()} 中、
 * 事件总线 {@code start()} 之后调用 {@link #refresh()} 触发。这样既去掉了「构造器里做 IO」的味道，
 * 也保证启动期告警一定发生在总线启动之后，不会因订阅者尚未注册而丢失。
 *
 * @author zcd
 */
@Singleton
public class RuntimeConfig {

    private final AppConfig appConfig;

    private final ConfigLoader configLoader;

    private final EventPublisher eventPublisher;

    /** 内置默认 agent 加载器。 */
    private final BuiltinAgentLoader builtinAgentLoader;

    /** 提示词加载器：把用户 agent 的 {@code {agentId}.md} 补进定义。 */
    private final AgentPromptLoader agentPromptLoader;

    /** 项目级配置的信任裁决：未信任的项目级文件不参与合并。 */
    private final ProjectConfigTrust projectConfigTrust;

    /** 当前配置快照，整体替换保证读取一致性。 */
    private volatile RuntimeSnapshot snapshot = RuntimeSnapshot.empty();

    /**
     * 构造运行时配置门面。
     * <p>
     * 构造器只保存依赖，不读取任何配置文件；配置由装配根在事件总线启动后调用 {@link #refresh()} 加载。
     *
     * @param appConfig      应用级配置，提供各配置段的双源路径
     * @param configLoader   配置文件读取门面
     * @param eventPublisher 通知发布入口，用于广播配置告警
     * @param builtinAgentLoader 内置默认 agent 加载器
     * @param agentPromptLoader  用户 agent 的提示词加载器
     * @param projectConfigTrust 项目级配置的信任裁决，决定项目级文件是否参与合并
     */
    @Inject
    public RuntimeConfig(AppConfig appConfig, ConfigLoader configLoader, EventPublisher eventPublisher,
                         BuiltinAgentLoader builtinAgentLoader, AgentPromptLoader agentPromptLoader,
                         ProjectConfigTrust projectConfigTrust) {
        this.appConfig = appConfig;
        this.configLoader = configLoader;
        this.eventPublisher = eventPublisher;
        this.builtinAgentLoader = Objects.requireNonNull(builtinAgentLoader,
                "builtinAgentLoader must not be null");
        this.agentPromptLoader = Objects.requireNonNull(agentPromptLoader,
                "agentPromptLoader must not be null");
        this.projectConfigTrust = Objects.requireNonNull(projectConfigTrust,
                "projectConfigTrust must not be null");
    }

    /**
     * 重新加载并合并全部配置段。
     * <p>
     * 首次加载由装配根在事件总线 {@code start()} 之后显式调用（见 {@code AgentHarness.bootstrap()}）；
     * 配置热更新时由上层再次调用。
     */
    public synchronized void refresh() {
        AgentDefinition systemAgent = builtinAgentLoader.load();
        ModelSettings mergedModel = load(appConfig.getModel(), ModelSettings.class, RuntimeConfig::mergeModelSettings);
        AgentSettings mergedAgents = loadAgentSettings();
        JellyfishSettings mergedJellyfish = load(appConfig.getJellyfish(), JellyfishSettings.class,
                RuntimeConfig::mergeJellyfishSettings);
        notifyIfInvalid(mergedModel);
        notifyIfInvalid(mergedJellyfish);
        this.snapshot = RuntimeSnapshot.of(mergedModel, mergedAgents, mergedJellyfish,
                pluginRootsOf(appConfig.getPlugins()), systemAgent);
    }

    /**
     * 加载并合并 agent 段：在通用双源合并的基础上，额外为每个自定义 agent 补上同名 md 提示词。
     * <p>
     * 不走通用 {@link #load} 是因为它只做「读取 + 合并」，而 agent 还需要一步「按条目 key 读提示词文件」；
     * 这一步<b>必须在合并前按各自源目录完成</b>——提示词的基准目录由「这份配置写在哪个文件」决定，
     * 合并之后就丢掉了来源信息。
     *
     * @return 合并后的 agent 配置，保证非 {@code null}
     */
    private AgentSettings loadAgentSettings() {
        ConfigPaths paths = appConfig.getAgent();
        String globalPath = paths == null ? null : paths.getGlobalPath();
        String projectPath = paths == null ? null : paths.getProjectPath();
        AgentSettings global = readAgents(globalPath);
        AgentSettings project = isSamePath(globalPath, projectPath) ? null : readAgentsTrusted(projectPath);
        return mergeAgentSettings(global, project);
    }

    /**
     * 读取项目级 agent 配置，但先过信任闸。
     * <p>
     * 与通用项目级读取同一道闸（见 {@link #readTrustedProject}），只是这里读完之后还要按条目补提示词，
     * 因此不能直接复用那一个方法。
     *
     * @param projectPath 项目级路径，可为 {@code null}
     * @return agent 配置；未信任或文件不存在时返回 {@code null}
     */
    private AgentSettings readAgentsTrusted(String projectPath) {
        ProjectConfigTrust.Decision decision = projectConfigTrust.decide(projectPath);
        if (decision == ProjectConfigTrust.Decision.LOAD) {
            return readAgents(projectPath);
        }
        if (decision == ProjectConfigTrust.Decision.UNTRUSTED) {
            notifyUntrustedProjectConfig(projectPath);
        }
        return null;
    }

    /**
     * 读取单个来源的 agent 配置并补上提示词。
     *
     * @param path 该来源的配置文件路径，可为空
     * @return 带提示词的配置；文件缺失时返回 {@code null}
     */
    private AgentSettings readAgents(String path) {
        AgentSettings settings = read(path, AgentSettings.class);
        return settings == null ? null : withPrompts(settings, agentPromptLoader.promptBaseOf(path));
    }

    /**
     * 为一份 agent 配置里的每个条目补上同名 md 提示词。
     * <p>
     * <b>非法 agentId 直接丢弃整条</b>：它同时是提示词文件名，含路径分隔符或 {@code ..} 的 key
     * 不该被拼进文件路径。这里不做「当成没有提示词继续用」的降级——那会留下一个名为 agent、
     * 实际没有身份提示词的条目，用户切过去会觉得「换了个 agent 却毫无变化」。
     *
     * @param settings 单源 agent 配置
     * @param base     该源的提示词基准目录，可为 {@code null}（无法定位，等同于无提示词）
     * @return 补上提示词后的配置
     */
    private AgentSettings withPrompts(AgentSettings settings, String base) {
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        for (Map.Entry<String, AgentDefinition> entry : settings.getAgents().entrySet()) {
            String agentId = entry.getKey();
            AgentDefinition definition = entry.getValue();
            if (definition == null) {
                continue;
            }
            if (!agentPromptLoader.isSafeAgentId(agentId)) {
                eventPublisher.publish(new ConfigWarningEvent(agentId,
                        "agentId 含路径分隔符等非法文件名字符，已忽略该条目"));
                continue;
            }
            agents.put(agentId, definition.withAgentId(agentId)
                    .withSystemPrompt(agentPromptLoader.load(base, agentId)));
        }
        return new AgentSettings(agents);
    }

    /**
     * 通用双源读取：分别读取 global 与 project 文件，再交给 {@code merger} 合并。
     * <p>
     * 任一文件缺失或解析结果为空时以 {@code null} 传给 {@code merger}，由 {@code merger} 负责空值语义；
     * 两路径相同时只读取一次（此时「项目级覆盖」等价于单源）。
     *
     * @param paths  该配置段的双源路径，可为 {@code null}
     * @param type   配置文件的绑定类型，不可为 {@code null}
     * @param merger 合并函数，入参依次为 global、project（均可能为 {@code null}），必须返回非 {@code null}
     * @param <T>    配置类型
     * @return 合并后的配置，保证非 {@code null}
     * @throws JellyfishException merger 返回 {@code null} 时抛出
     */
    <T> T load(ConfigPaths paths, Class<T> type, BinaryOperator<T> merger) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(merger, "merger must not be null");
        String globalPath = paths == null ? null : paths.getGlobalPath();
        String projectPath = paths == null ? null : paths.getProjectPath();
        T global = read(globalPath, type);
        T project;
        if (isSamePath(globalPath, projectPath)) {
            // 同一份文件读一次即可，避免重复解析并产生两套等价对象
            project = null;
        } else {
            project = readTrustedProject(projectPath, type);
        }
        T merged = merger.apply(global, project);
        if (merged == null) {
            throw new JellyfishException("config merger returned null for type: " + type.getName());
        }
        return merged;
    }

    /**
     * 读取项目级配置，但先过信任闸。
     * <p>
     * 项目级配置的读取基准是进程当前目录，因此它的内容会随「在哪个仓库里启动」而变化；
     * 而它能改 provider 的 {@code baseUrl}/{@code apiKey}、新增 agent、改落盘目录。
     * 因此没被显式信任过的项目级文件一律跳过（详见 {@link ProjectConfigTrust}），
     * 并且只留一条告警——静默跳过会让用户以为自己的配置生效了。
     *
     * @param projectPath 项目级路径，可为 {@code null}
     * @param type        绑定类型，不可为 {@code null}
     * @param <T>         配置类型
     * @return 绑定结果；未信任或文件不存在时返回 {@code null}
     */
    private <T> T readTrustedProject(String projectPath, Class<T> type) {
        ProjectConfigTrust.Decision decision = projectConfigTrust.decide(projectPath);
        if (decision == ProjectConfigTrust.Decision.LOAD) {
            return read(projectPath, type);
        }
        if (decision == ProjectConfigTrust.Decision.UNTRUSTED) {
            notifyUntrustedProjectConfig(projectPath);
        }
        return null;
    }

    /**
     * 对「项目级配置存在但未被信任」发出告警。
     * <p>
     * 消息里带上解锁方式：只说「没加载」而不说怎么加载，用户面前就是一个没有出口的死角。
     *
     * @param projectPath 项目级路径，可为 {@code null}
     */
    private void notifyUntrustedProjectConfig(String projectPath) {
        eventPublisher.publish(new ConfigWarningEvent("project-config",
                "项目级配置未加载（未被信任）：" + projectPath
                        + "；确认无误后用 --trust-project-config 启动，或在 TUI 里选择加载"));
    }

    /**
     * 获取合并后的 provider 列表。
     *
     * @return provider 列表，可能为空但不会为 {@code null}
     */
    public List<Provider> getProviders() {
        return snapshot.getProviders();
    }

    /**
     * 获取合并后的默认 provider 名。
     *
     * @return 默认 provider 名，未配置时为 {@code null}
     */
    public String getDefaultProvider() {
        return snapshot.getModelSettings().getDefaultProvider();
    }

    /**
     * 获取合并后的默认 model 名。
     *
     * @return 默认 model 名，未配置时为 {@code null}
     */
    public String getDefaultModel() {
        return snapshot.getModelSettings().getDefaultModel();
    }

    /**
     * 获取合并后的模型配置快照。
     *
     * @return 模型配置，保证非 {@code null} 且只读
     */
    public ModelSettings getModelSettings() {
        return snapshot.getModelSettings();
    }

    /**
     * 获取合并后的 agent 配置快照。
     *
     * @return agent 配置，保证非 {@code null} 且只读
     */
    public AgentSettings getAgentSettings() {
        return snapshot.getAgentSettings();
    }

    /**
     * 获取内置默认 agent。
     * <p>
     * 与 {@link #getAgentSettings()} 分开：内置 agent 不来自任何双源配置文件，
     * 是随构件发布的一份常量定义，会话创建时恒绑它。
     *
     * @return 内置默认 agent；尚未加载配置快照时为 {@code null}
     */
    public AgentDefinition getSystemAgent() {
        return snapshot.getSystemAgent();
    }

    /**
     * 获取合并后的运行期设置快照。
     *
     * @return 运行期设置，保证非 {@code null} 且只读
     */
    public JellyfishSettings getJellyfishSettings() {
        return snapshot.getJellyfishSettings();
    }

    /**
     * 获取合并后的 ReAct 段。
     *
     * @return ReAct 段，保证非 {@code null}
     */
    public ReactSettings getReactSettings() {
        return snapshot.getJellyfishSettings().getReact();
    }

    /**
     * 获取权限段。
     * <p>
     * 审批超时每轮现读而不缓存：配置刷新后无需重启即可生效，与「不持有全局当前态」同口径。
     *
     * @return 权限段，保证非 {@code null}
     */
    public PermissionApprovalSettings getPermissionApprovalSettings() {
        return snapshot.getJellyfishSettings().getPermission();
    }

    /**
     * 获取子代理委派段。
     * <p>
     * 与审批超时同口径：四个参数每轮现读而不缓存，关掉开关或改上限后无需重启即可生效。
     *
     * @return 子代理委派段，保证非 {@code null}
     */
    public SubAgentSettings getSubAgentSettings() {
        return snapshot.getJellyfishSettings().getSubAgent();
    }

    /**
     * 获取向用户提问段。
     * <p>
     * 与审批超时同口径：超时值每次提问现读而不缓存，改配置后无需重启即可生效。
     *
     * @return 提问段，保证非 {@code null}
     */
    public AskSettings getAskSettings() {
        return snapshot.getJellyfishSettings().getAsk();
    }

    /**
     * 获取合并后的插件段。
     * <p>
     * 不重复存放：插件段随 {@link JellyfishSettings} 一起进快照，这里只是转发。
     *
     * @return 插件段，保证非 {@code null} 且只读
     */
    public PluginsSettings getPluginsSettings() {
        return snapshot.getJellyfishSettings().getPlugins();
    }

    /**
     * 获取插件扫描根目录。
     * <p>
     * 来源是 {@code config.json} 的 {@code plugins.roots}，不经双源合并；条目行首的 {@code ~}
     * 在这里展开为用户主目录（与 {@code models.json} / {@code agents.json} 的路径同一套规则）。
     * 空白条目在此处丢弃。
     *
     * @return 不可变目录列表；未配置或全为空白时为空列表，由 {@code PluginRuntimeConfig} 回退默认目录
     */
    public List<Path> getPluginRoots() {
        return snapshot.getPluginRoots();
    }

    /**
     * 读取单个配置段，并对「路径已配置但读不到内容」与「字段名不认识」发出告警事件。
     * <p>
     * 未知字段那条是刻意的：绑定容忍未知字段（配置文件要向前兼容），于是拼错一个字段名完全没有提示，
     * 现场表现只是「配置明明写了却没作用」。这里把文件路径与字段名一起报出来，用户才找得到。
     *
     * @param path 完整文件路径，可为空
     * @param type 绑定类型
     * @param <T>  配置类型
     * @return 解析结果；路径为空或文件缺失时返回 {@code null}
     */
    private <T> T read(String path, Class<T> type) {
        T value = configLoader.read(path, type, field -> eventPublisher.publish(new ConfigWarningEvent(path,
                "配置里有内核不认识的字段：" + field + "（拼错了？它会被静默忽略）")));
        if (value == null && StringUtils.isNotBlank(path)) {
            eventPublisher.publish(new ConfigWarningEvent(path, "配置段文件缺失或为空，将按未配置处理"));
        }
        return value;
    }

    /**
     * 判断 global 与 project 是否指向同一个已配置路径。
     *
     * @param globalPath  全局级路径，可为 {@code null}
     * @param projectPath 项目级路径，可为 {@code null}
     * @return 两者均为非空且字符串相等时返回 {@code true}
     */
    private static boolean isSamePath(String globalPath, String projectPath) {
        return StringUtils.isNotBlank(globalPath) && globalPath.equals(projectPath);
    }

    /**
     * 合并全局级与项目级模型配置，产出不可变结果。
     *
     * @param global  全局级配置，可为 {@code null}
     * @param project 项目级配置，可为 {@code null}
     * @return 合并结果，保证非 {@code null}
     */
    private static ModelSettings mergeModelSettings(ModelSettings global, ModelSettings project) {
        Map<String, Provider> providers = new LinkedHashMap<>();
        putProviders(providers, global);
        // 同名 provider 整对象替换：项目级覆盖全局级，顺序保留全局级原有位置
        putProviders(providers, project);
        String defaultProvider = override(project, global, ModelSettings::getDefaultProvider);
        String defaultModel = override(project, global, ModelSettings::getDefaultModel);
        return new ModelSettings(defaultProvider, defaultModel, providers);
    }

    /**
     * 把一份配置中的 provider 以不可变副本写入目标映射，并用 map 的 key 回填 provider 名称，
     * 不修改原始 {@link Provider} 对象。
     *
     * @param target   目标映射
     * @param settings 待合并配置，可为 {@code null}
     */
    private static void putProviders(Map<String, Provider> target, ModelSettings settings) {
        if (settings == null) {
            return;
        }
        for (Map.Entry<String, Provider> entry : settings.getProviders().entrySet()) {
            Provider provider = entry.getValue();
            if (provider == null) {
                continue;
            }
            target.put(entry.getKey(), provider.withName(entry.getKey()));
        }
    }

    /**
     * 合并全局级与项目级 agent 配置，产出不可变结果。
     *
     * @param global  全局级配置，可为 {@code null}
     * @param project 项目级配置，可为 {@code null}
     * @return 合并结果，保证非 {@code null}
     */
    private static AgentSettings mergeAgentSettings(AgentSettings global, AgentSettings project) {
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        putAgents(agents, global);
        // 同名 agent 整对象替换，理由同 provider：逐字段合并会让「一半授权来自全局、一半来自项目」无法审计
        putAgents(agents, project);
        return new AgentSettings(agents);
    }

    /**
     * 把一份配置中的 agent 以不可变副本写入目标映射，并用 map 的 key 回填 agentId。
     *
     * @param target   目标映射
     * @param settings 待合并配置，可为 {@code null}
     */
    private static void putAgents(Map<String, AgentDefinition> target, AgentSettings settings) {
        if (settings == null) {
            return;
        }
        for (Map.Entry<String, AgentDefinition> entry : settings.getAgents().entrySet()) {
            AgentDefinition definition = entry.getValue();
            if (definition == null) {
                continue;
            }
            target.put(entry.getKey(), definition.withAgentId(entry.getKey()));
        }
    }

    /**
     * 合并全局级与项目级运行期设置，产出不可变结果。
     * <p>
     * 五个段一律「项目级<b>写了这一段</b>才覆盖，没写就整段回退全局级」：判据是
     * {@link JellyfishSettings#isPluginsDeclared()} 这类声明标记，而不是「取到的段非空」——
     * 后者永远成立（构造器会把缺失的段填成缺省对象），于是「项目级没写这一段」会被当成
     * 「写了、内容全是缺省值」，把全局级配好的整段静默顶掉。
     *
     * @param global  全局级配置，可为 {@code null}
     * @param project 项目级配置，可为 {@code null}
     * @return 合并结果，保证非 {@code null}
     */
    private static JellyfishSettings mergeJellyfishSettings(JellyfishSettings global, JellyfishSettings project) {
        return new JellyfishSettings(
                mergePluginsSettings(pluginsOf(global), pluginsOf(project)),
                mergeReactSettings(reactOf(global), reactOf(project)),
                mergePermissionSettings(permissionOf(global), permissionOf(project)),
                mergeSubAgentSettings(subAgentOf(global), subAgentOf(project)),
                mergeAskSettings(askOf(global), askOf(project)));
    }

    /**
     * 取一份运行期设置里的提问段。
     * <p>
     * <b>没写这一段时返回 {@code null}</b>：口径与 {@link #reactOf(JellyfishSettings)} 相同，
     * 判据是 {@link JellyfishSettings#isAskDeclared()}。
     *
     * @param settings 运行期设置，可为 {@code null}
     * @return 提问段；该份文件未配置这一段时为 {@code null}
     */
    private static AskSettings askOf(JellyfishSettings settings) {
        return settings != null && settings.isAskDeclared() ? settings.getAsk() : null;
    }

    /**
     * 合并全局级与项目级提问段。
     * <p>
     * 与 provider / agent / react / permission / subAgent 同口径的「整对象覆盖」：项目级非空则整体
     * 替换全局级，否则回退全局级，两者都缺省时由 {@link JellyfishSettings} 的构造器落到缺省值。
     *
     * @param global  全局级提问段，可为 {@code null}
     * @param project 项目级提问段，可为 {@code null}
     * @return 合并结果，可能为 {@code null}（表示用缺省值）
     */
    private static AskSettings mergeAskSettings(AskSettings global, AskSettings project) {
        return project != null ? project : global;
    }

    /**
     * 取一份运行期设置里的子代理段。
     * <p>
     * <b>没写这一段时返回 {@code null}</b>：口径与 {@link #reactOf(JellyfishSettings)} 相同，
     * 判据是 {@link JellyfishSettings#isSubAgentDeclared()}。
     *
     * @param settings 运行期设置，可为 {@code null}
     * @return 子代理段；该份文件未配置这一段时为 {@code null}
     */
    private static SubAgentSettings subAgentOf(JellyfishSettings settings) {
        return settings != null && settings.isSubAgentDeclared() ? settings.getSubAgent() : null;
    }

    /**
     * 合并全局级与项目级子代理段。
     * <p>
     * 与 provider / agent / react / permission 同口径的「整对象覆盖」：项目级非空则整体替换全局级，
     * 否则回退全局级，两者都缺省时由 {@link JellyfishSettings} 的构造器落到缺省值。
     * 不做逐字段合并：这些参数互相牵制（关掉开关时其余各项无意义），
     * 「一半来自全局、一半来自项目」会让「这个项目到底允许多深的委派」无法从任何单份文件看出来。
     *
     * @param global  全局级子代理段，可为 {@code null}
     * @param project 项目级子代理段，可为 {@code null}
     * @return 合并结果，可能为 {@code null}（表示用缺省值）
     */
    private static SubAgentSettings mergeSubAgentSettings(SubAgentSettings global, SubAgentSettings project) {
        return project != null ? project : global;
    }

    /**
     * 取一份运行期设置里的权限段。
     * <p>
     * <b>没写这一段时返回 {@code null}</b>：口径与 {@link #reactOf(JellyfishSettings)} 相同，
     * 判据是 {@link JellyfishSettings#isPermissionDeclared()}。
     *
     * @param settings 运行期设置，可为 {@code null}
     * @return 权限段；该份文件未配置这一段时为 {@code null}
     */
    private static PermissionApprovalSettings permissionOf(JellyfishSettings settings) {
        return settings != null && settings.isPermissionDeclared() ? settings.getPermission() : null;
    }

    /**
     * 合并全局级与项目级权限段。
     * <p>
     * 与 provider / agent / react 同口径的「整对象覆盖」：项目级非空则整体替换全局级，
     * 否则回退全局级，两者都缺省时由 {@link JellyfishSettings} 的构造器落到缺省值。
     *
     * @param global  全局级权限段，可为 {@code null}
     * @param project 项目级权限段，可为 {@code null}
     * @return 合并结果，可能为 {@code null}（表示用缺省值）
     */
    private static PermissionApprovalSettings mergePermissionSettings(PermissionApprovalSettings global,
                                                                       PermissionApprovalSettings project) {
        return project != null ? project : global;
    }

    /**
     * 取一份运行期设置里的 ReAct 段。
     * <p>
     * <b>没写这一段时返回 {@code null}</b>：{@code react} / {@code permission} / {@code subAgent}
     * / {@code ask} 四段是「整对象」覆盖，因此必须把「项目级没写这一段」与「写了、只是内容恰好
     * 与缺省值相同」分开——否则一份只写了别的段的项目级文件会把全局级的这一整段顶成缺省值，
     * 而用户看不出任何痕迹。判据是 {@link JellyfishSettings#isReactDeclared()}。
     *
     * @param settings 运行期设置，可为 {@code null}
     * @return ReAct 段；该份文件未配置这一段时为 {@code null}
     */
    private static ReactSettings reactOf(JellyfishSettings settings) {
        return settings != null && settings.isReactDeclared() ? settings.getReact() : null;
    }

    /**
     * 合并全局级与项目级 ReAct 段。
     * <p>
     * 与 provider / agent 同口径的「整对象覆盖」：项目级非空则整体替换全局级，否则回退全局级，
     * 两者都缺省时由 {@link JellyfishSettings} 的构造器落到缺省值。不做逐字段合并，
     * 避免「一半参数来自全局、一半来自项目」这种无法审计的混合态。
     *
     * @param global  全局级 ReAct 段，可为 {@code null}
     * @param project 项目级 ReAct 段，可为 {@code null}
     * @return 合并结果，可能为 {@code null}（表示用缺省值）
     */
    private static ReactSettings mergeReactSettings(ReactSettings global, ReactSettings project) {
        return project != null ? project : global;
    }

    /**
     * 取一份运行期设置里的插件段。
     * <p>
     * <b>没写这一段时返回 {@code null}</b>：口径与 {@link #reactOf(JellyfishSettings)} 相同，
     * 判据是 {@link JellyfishSettings#isPluginsDeclared()}。段内的名单与各插件配置段另有各自的
     * 声明判定（见 {@link PluginsSettings}），那一级不受本方法影响。
     *
     * @param settings 运行期设置，可为 {@code null}
     * @return 插件段；该份文件未配置这一段时为 {@code null}
     */
    private static PluginsSettings pluginsOf(JellyfishSettings settings) {
        return settings != null && settings.isPluginsDeclared() ? settings.getPlugins() : null;
    }

    /**
     * 合并全局级与项目级插件段。
     *
     * @param global  全局级插件段，可为 {@code null}
     * @param project 项目级插件段，可为 {@code null}
     * @return 合并结果，保证非 {@code null}
     */
    private static PluginsSettings mergePluginsSettings(PluginsSettings global, PluginsSettings project) {
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        Map<String, Map<String, Object>> globalConfigurations = new LinkedHashMap<>();
        Set<String> projectDeclared = new LinkedHashSet<>();
        putPluginConfigurations(configurations, global);
        // 全局级那一份单独留底：下面同名插件段会被项目级整对象替换掉，而安全边界类的键只能认它
        if (global != null) {
            putPluginConfigurations(globalConfigurations, global);
        }
        // 同名插件配置段整对象替换：插件声明的名单类配置必须整段生效或整段不生效，不能半新半旧
        putPluginConfigurations(configurations, project);
        if (project != null) {
            projectDeclared.addAll(project.getConfigurations().keySet());
        }
        return new PluginsSettings(
                listOverride(project, global, PluginsSettings::isEnabledDeclared, PluginsSettings::getEnabled),
                listOverride(project, global, PluginsSettings::isDisabledDeclared, PluginsSettings::getDisabled),
                configurations, globalConfigurations, projectDeclared);
    }

    /**
     * 把 {@code config.json} 的插件扫描目录转成路径列表，展开 {@code ~} 并丢弃空白条目。
     * <p>
     * 与双源配置段不同，这里不做合并：{@code plugins.roots} 只写在 {@code config.json} 一处，
     * 天然只有一份真相。
     *
     * @param plugins 插件扫描段，可为 {@code null}（视为未配置）
     * @return 不可变路径列表；未配置或全为空白时为空列表
     */
    private static List<Path> pluginRootsOf(PluginPaths plugins) {
        if (plugins == null || plugins.getRoots().isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> roots = new ArrayList<>(plugins.getRoots().size());
        for (String root : plugins.getRoots()) {
            if (root != null && !root.trim().isEmpty()) {
                roots.add(Paths.get(HomePaths.expand(root.trim())));
            }
        }
        return roots.isEmpty() ? Collections.<Path>emptyList() : Collections.unmodifiableList(roots);
    }

    /**
     * 把一份插件段里的各插件配置写入目标映射。
     *
     * @param target   目标映射
     * @param settings 待合并插件段，可为 {@code null}
     */
    private static void putPluginConfigurations(Map<String, Map<String, Object>> target,
                                                PluginsSettings settings) {
        if (settings == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : settings.getConfigurations().entrySet()) {
            if (entry.getValue() != null) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 取项目级非空字符串，缺省时回退全局级。
     *
     * @param project  项目级配置，可为 {@code null}
     * @param global   全局级配置，可为 {@code null}
     * @param accessor 取值函数
     * @param <T>      配置类型
     * @return 项目级非空值，否则全局级值
     */
    private static <T> String override(T project, T global, Function<T, String> accessor) {
        String projectValue = project == null ? null : accessor.apply(project);
        if (StringUtils.isNotBlank(projectValue)) {
            return projectValue;
        }
        return global == null ? null : accessor.apply(global);
    }

    /**
     * 取「已声明」的项目级列表，未声明时回退全局级。
     * <p>
     * 判据是「有没有声明」而不是「非空」：项目级显式写 {@code []} 的语义是「本层一个都不要」，
     * 按非空判断会把它当成未配置而回退全局级，用户就没法在项目里收窄掉全局名单。
     * 与「非空则整体替换」一致的地方是：仍然不做并集，因为合并会让「收窄」做不到。
     *
     * @param project  项目级插件段，可为 {@code null}
     * @param global   全局级插件段，可为 {@code null}
     * @param declared 声明判定函数
     * @param accessor 取值函数
     * @return 项目级已声明的列表，否则全局级列表（可能为 {@code null}）
     */
    private static List<String> listOverride(PluginsSettings project, PluginsSettings global,
                                             Predicate<PluginsSettings> declared,
                                             Function<PluginsSettings, List<String>> accessor) {
        if (project != null && declared.test(project)) {
            return accessor.apply(project);
        }
        return global == null ? null : accessor.apply(global);
    }

    /**
     * 对合并后的模型配置做一致性告警：默认值指向不存在的 provider / model 时只发出
     * {@link ConfigWarningEvent}，交由选择模型的 {@code ModelManager} 在真正用到时决定如何处理。
     * <p>
     * 每个 provider 的 {@code sampling} 段也在这里一并报：采样值非法时内核是「静默丢弃该项」
     * （各家对 {@code top_k = 0} 的语义都不一样，因此不猜），而「丢掉了」这件事必须说出来，
     * 否则用户只会看到「我调了温度却没变化」。
     *
     * @param merged 合并后的模型配置
     */
    private void notifyIfInvalid(ModelSettings merged) {
        Map<String, Provider> providers = merged.getProviders();
        if (providers.isEmpty()) {
            eventPublisher.publish(new ConfigWarningEvent("model", "模型配置未包含任何 provider"));
            return;
        }
        for (Provider provider : providers.values()) {
            if (StringUtils.isBlank(provider.getType())) {
                eventPublisher.publish(new ConfigWarningEvent(provider.getName(),
                        "provider 缺少 type，无法路由到具体客户端"));
            }
            for (String warning : provider.getSampling().warnings()) {
                eventPublisher.publish(new ConfigWarningEvent(provider.getName(), warning));
            }
        }
        String defaultProvider = merged.getDefaultProvider();
        String defaultModel = merged.getDefaultModel();
        if (StringUtils.isNotBlank(defaultProvider) && !providers.containsKey(defaultProvider)) {
            eventPublisher.publish(new ConfigWarningEvent("model",
                    "默认 provider [" + defaultProvider + "] 不存在于 providers 中"));
            return;
        }
        if (StringUtils.isNotBlank(defaultModel) && !hasModel(providers, defaultProvider, defaultModel)) {
            eventPublisher.publish(new ConfigWarningEvent("model",
                    "默认 model [" + defaultModel + "] 未在任何 provider 中定义"));
        }
    }

    /**
     * 对合并后的运行期设置做一致性告警。
     * <p>
     * 查两处真实歧义：同一个 pluginId 同时出现在启用与禁用名单里（此时按既有语义「禁用优先」
     * 处理是对的，但用户多半写错了）；以及权限段与提问段的等待超时被写成负数——那是非法值，
     * 会被换成缺省 120 秒，而「我以为它立刻生效 / 立刻拒绝」与「它其实等了 120 秒」在外表上一样。
     * <p>
     * <b>数值项写错也在这条通道上</b>：{@code react} 段（含 {@code react.toolOutput}）、
     * {@code permission} 段与 {@code ask} 段把每一次「非法值回退缺省」、以及「刻意配成永不超时」
     * 都记在 {@code warnings()} 里，由这里逐条发出去。它们此前要么只写日志
     * （TUI 下日志只进文件），要么什么都不说——用户调了半天配置没生效，其实是那个值一开始
     * 就被判非法了。事件面是配置问题的<b>唯一出口</b>：设置类不该各自长出一条发布路径。
     *
     * @param merged 合并后的运行期设置
     */
    private void notifyIfInvalid(JellyfishSettings merged) {
        PluginsSettings plugins = merged.getPlugins();
        Set<String> conflicted = new LinkedHashSet<>(plugins.getEnabled());
        conflicted.retainAll(plugins.getDisabled());
        for (String pluginId : conflicted) {
            eventPublisher.publish(new ConfigWarningEvent(pluginId,
                    "插件同时出现在启用与禁用名单中，按禁用处理"));
        }
        for (String warning : merged.getPermission().warnings()) {
            eventPublisher.publish(new ConfigWarningEvent("permission", warning));
        }
        for (String warning : merged.getAsk().warnings()) {
            eventPublisher.publish(new ConfigWarningEvent("ask", warning));
        }
        for (String warning : merged.getReact().warnings()) {
            eventPublisher.publish(new ConfigWarningEvent("react", warning));
        }
    }

    /**
     * 判断指定默认值下是否存在目标 model。
     *
     * @param providers       provider 映射
     * @param defaultProvider 默认 provider 名，可为空
     * @param modelName       目标 model 名
     * @return 存在返回 {@code true}
     */
    private static boolean hasModel(Map<String, Provider> providers, String defaultProvider, String modelName) {
        if (StringUtils.isNotBlank(defaultProvider)) {
            Provider provider = providers.get(defaultProvider);
            return provider != null && containsModel(provider, modelName);
        }
        for (Provider provider : providers.values()) {
            if (containsModel(provider, modelName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 provider 是否提供指定 model。
     *
     * @param provider  provider
     * @param modelName model 名
     * @return 提供返回 {@code true}
     */
    private static boolean containsModel(Provider provider, String modelName) {
        for (Model model : provider.getModels()) {
            if (model != null && modelName.equals(model.getName())) {
                return true;
            }
        }
        return false;
    }
}
