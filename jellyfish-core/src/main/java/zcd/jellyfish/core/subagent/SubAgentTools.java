package zcd.jellyfish.core.subagent;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigReloadedEvent;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 子代理能力的注册器：把 {@code task} 工具与「可委派类型清单」挂到内核的扩展点上。
 * <p>
 * <b>为什么由内核注册而不是一个插件</b>：子代理改的是<b>循环的结构</b>（回合可以调用自己），
 * 不是增加一片叶子能力；而且 {@code task} 的类型清单要由 {@code AgentManager} 现算，
 * 插件拿不到它——让插件自己维护一份，必然会与 {@code agents.json} 漂移。
 * 这与系统命令由 {@code core/command/SystemCommands} 注册是同一个形态：内核作为扩展点的使用者。
 * <p>
 * <b>owner 固定为 {@code core}</b>：与 {@code pluginId} 区分开，插件若要替换 {@code task}
 * 必须显式声明 {@code override}——静默覆盖一个内核工具比覆盖一个命令更危险。
 * <p>
 * <b>清单走提示词贡献而不是工具 enum</b>：{@code ToolDescriptor} 在注册那一刻就固定了，
 * 而可委派的 agent 会随 {@code /reload} 变；提示词贡献每轮现算，天然跟随配置，
 * 也不需要「配置变了就重新注册一次描述符」这种时序约定。
 * <p>
 * 没有任何 agent 声明 {@code delegatable} 时贡献为空——零开销，且模型不会看到一段
 * 「可用类型：」后面什么都没有的废话。
 * <p>
 * <b>开关关掉时连工具一起摘掉</b>：只让清单变空是不够的——模型依然看得到 {@code task}，
 * 却没有一个合法的 {@code subagent_type} 可传，于是每个会话白调一次、拿一条「已被禁用」。
 * 而注册只能在启动窗口内发生，因此这里必须自己听 {@link ConfigReloadedEvent}：
 * 开关是靠 {@code /reload} 改的，不重算就只会在下次重启时才生效。
 * <p>
 * <b>重算的投递是 best-effort</b>：{@code EventChannel} 可丢，丢了的表现是「开关已翻转但
 * 工具仍按旧状态在清单里」——委派本身仍会被 {@code SubAgentLauncher} 拒掉，因此这是外观
 * 陈旧而不是放行，不值得为它去把事件通道改成不可丢。
 *
 * <b>面板也挂在这里</b>：{@link SubAgentPanel} 是「子代理现在在跑什么」的观测面，与工具同一开关、
 * 同一个 owner。它不额外占订阅槽：注销时跟着 {@code task} 一起被摘掉。
 *
 * @author zcd
 */
@Singleton
public class SubAgentTools {

    /** 内核注册的 owner 标识。 */
    public static final String OWNER = "core";

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions;

    /** 异步通知通道，仅用于听配置重载。 */
    private final EventChannel events;

    /** agent 门面：现算可委派类型。 */
    private final AgentManager agentManager;

    /** 运行时配置门面：读全局开关。 */
    private final RuntimeConfig runtimeConfig;

    /** {@code task} 工具本体。 */
    private final TaskTool taskTool;

    /** 运行面板：把本会话在跑的子代理贴到界面上。 */
    private final SubAgentPanel panel;

    /** 已注册的句柄，重算与 {@link #close()} 时回收。 */
    private final List<Subscription> subscriptions = new ArrayList<Subscription>();

    /** 配置重载监听句柄；与 {@link #subscriptions} 分开，重算不能把自己的监听一起关掉。 */
    private Subscription reloadSubscription;

    /**
     * 构造注册器。
     *
     * @param extensions    同步扩展点策略，不可为 {@code null}
     * @param events        异步通知通道，不可为 {@code null}
     * @param agentManager  agent 门面，不可为 {@code null}
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     * @param taskTool      {@code task} 工具本体，不可为 {@code null}
     * @param panel         运行面板，不可为 {@code null}
     */
    @Inject
    public SubAgentTools(ExtensionRegistry extensions, EventChannel events, AgentManager agentManager,
                         RuntimeConfig runtimeConfig, TaskTool taskTool, SubAgentPanel panel) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.taskTool = Objects.requireNonNull(taskTool, "taskTool must not be null");
        this.panel = Objects.requireNonNull(panel, "panel must not be null");
    }

    /**
     * 按当前配置挂上（或摘掉）{@code task} 工具与可委派类型清单，并开始听配置重载。
     * <p>
     * 必须排在插件启动<b>之前</b>：插件要覆盖 {@code task} 得先有东西可覆盖，
     * 否则它会以 {@code DUPLICATE_HANDLER} 反过来暴露给用户。
     */
    public synchronized void register() {
        if (reloadSubscription == null) {
            // 监听无论开关是否开启都要挂：关着的时候才是它唯一有用的时刻
            reloadSubscription = events.subscribe(OWNER, ConfigReloadedEvent.class, event -> reconcile());
        }
        reconcile();
    }

    /**
     * 回收全部注册与监听。
     */
    public synchronized void close() {
        unregister();
        if (reloadSubscription != null) {
            reloadSubscription.close();
            reloadSubscription = null;
        }
    }

    /**
     * 把注册状态对齐到当前配置：开关关着就不该让模型看到这个工具。
     * <p>
     * <b>幂等的原因与注册幂等不同</b>：这里每次重算都会先看「当前是否已注册」，
     * 因此连续多次 {@code /reload} 不会重复注册（重复注册会因同键冲突当场抛错）。
     * <p>
     * 加锁是因为它有两个调用者：启动期的 {@link #register()} 与事件通道的派发线程。
     * 两者拿的是同一把锁（{@code synchronized} 可重入），因此重算不会并发改注册表。
     */
    private synchronized void reconcile() {
        if (!runtimeConfig.getSubAgentSettings().isEnabled()) {
            unregister();
            return;
        }
        if (!subscriptions.isEmpty()) {
            return;
        }
        subscriptions.add(extensions.handle(OWNER, ToolCallRequest.class, TaskTool.NAME, taskTool.descriptor(),
                taskTool, RegisterOptions.DEFAULT));
        subscriptions.add(extensions.contribute(OWNER, PromptContributionRequest.class, null,
                this::catalog, RegisterOptions.DEFAULT));
        // 面板与工具同生共死：没有 task 工具就没有子代理，注册一块永远为空的面板只会白占区域
        subscriptions.add(extensions.contribute(OWNER, PanelContributionRequest.class, null,
                panel, RegisterOptions.DEFAULT));
    }

    /**
     * 摘掉已注册的工具与清单。
     */
    private void unregister() {
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
    }

    /**
     * 贡献「可委派的子代理类型」清单。
     * <p>
     * 只列类型与一句描述，不重复 {@code task} 工具描述里已经写过的使用建议——那段跟着工具名片
     * 每轮都在，这里再写一遍只是多花 token。
     * <p>
     * <b>开关为什么在这里再读一次</b>：{@link #reconcile()} 已经把「关掉就不注册」做了一遍，
     * 但两者之间有一段窗口——{@code /reload} 刚改完配置、重算事件还在队列里，而模型此刻
     * 正在组装请求。这时若只靠注册状态，清单会按旧状态多宣传一个已关掉的能力。
     *
     * @param request 贡献请求（本处理器只读，不用它携带的会话标识）
     * @return 贡献结果；禁用或没有可委派类型时为空贡献
     */
    private PromptContribution catalog(PromptContributionRequest request) {
        if (!runtimeConfig.getSubAgentSettings().isEnabled()) {
            return PromptContribution.empty();
        }
        StringBuilder text = new StringBuilder("可委派的子代理类型（task 工具的 subagent_type 取其一）：");
        int listed = 0;
        for (AgentDefinition definition : agentManager.all()) {
            if (definition == null || !definition.isDelegatable()) {
                continue;
            }
            text.append('\n').append("- ").append(definition.getAgentId());
            if (StringUtils.isNotBlank(definition.getDescription())) {
                text.append("：").append(definition.getDescription().trim());
            }
            listed++;
        }
        return listed == 0 ? PromptContribution.empty() : PromptContribution.of(text.toString());
    }
}
