package zcd.jellyfish.infra.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.PluginStateChangedEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * UI 贡献门面：外壳向插件收集界面内容、并订阅「内容可能已过期」的唯一入口。
 * <p>
 * <b>为什么要有这一层</b>：外壳只应认识「给我一份内容」这个动作，不应该认识 {@code ExtensionRegistry}
 * 与 {@code EventChannel}——与 {@code CommandManager} 是同一条口径（注册表与派发策略是 infra 的事）。
 * <p>
 * <b>两类贡献的区别只在「能否共存」</b>：状态栏片段是拼接型（多插件共存，去重后拼接），
 * 面板是独占型（一块区域同时只显示一个，因此带上 owner 交给外壳／用户仲裁）。
 * <p>
 * <b>调用模型是「失效时收集」而不是「每帧收集」</b>：插件处理器只在缓存失效时被调用，
 * 因此空闲时为零调用。代价是「漏一个失效触发源 = 内容永久陈旧」，所以外壳必须把
 * 会话切换、回合开始/收敛、命令执行、插件加载卸载都当作失效（见 {@code TuiApp}）。
 * <p>
 * 本类对插件的失败一律隔离：单个处理器抛错只记告警并跳过它自己，其余插件照常出内容——
 * 收集是全量的，一个坏插件若不隔离，就会把「一个插件坏了」放大成「整个界面没内容」。
 * <p>
 * <b>生命周期</b>：{@link #close()} 幂等，只解除本类自己建立的订阅，<b>不清空共用注册表</b>
 * （那是插件回收的职责），因此关闭门面不会顺手抹掉同步侧注册。
 * <p>
 * 可安全跨线程使用：收集在渲染线程，失效通知可能在事件通道的订阅者线程。
 *
 * @author zcd
 */
public final class UiContributions implements AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(UiContributions.class);

    /** 同步扩展点策略（同一份注册表的同步视图）。 */
    private final ExtensionRegistry extensions;

    /** 事件通道：失效订阅落在这里。 */
    private final EventChannel events;

    /** 订阅来源标识（外壳名，如 {@code tui}），用于反注册与告警归因。 */
    private final String owner;

    /** 本类建立的订阅，{@link #close()} 时逐个解除。 */
    private final List<Subscription> subscriptions = new ArrayList<Subscription>();

    /** 是否正在收集：只为把「处理器违规发布失效事件」这个事实记成告警，不影响通知是否送达。 */
    private volatile boolean collecting;

    /**
     * 构造 UI 贡献门面。
     *
     * @param extensions 同步扩展点策略，不可为 {@code null}
     * @param events     事件通道，不可为 {@code null}
     * @param owner      订阅来源标识（外壳名），不可为空白
     * @throws JellyfishException 来源标识为空白时抛出
     */
    public UiContributions(ExtensionRegistry extensions, EventChannel events, String owner) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        if (owner == null || owner.trim().isEmpty()) {
            throw new JellyfishException("ui contributions owner must not be blank");
        }
        this.owner = owner;
    }

    /**
     * 收集一次某会话的全部 UI 贡献（状态栏片段 + 面板）。
     * <p>
     * 在调用点线程内联执行插件处理器，因此外壳只应在渲染线程调用；处理器抛错只记告警并跳过。
     * 结果里<b>不做区域仲裁</b>：哪个插件的面板显示在哪里还要考虑用户的 {@code /ui} 选择，那是外壳的状态。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 快照，保证非 {@code null}
     */
    public UiSnapshot collect(String sessionId) {
        collecting = true;
        try {
            return UiSnapshot.of(statusFragments(sessionId), panels(sessionId));
        } finally {
            collecting = false;
        }
    }

    /**
     * 订阅「我的内容可能已过期」。
     * <p>
     * 覆盖两个来源：插件主动发布的 {@link UiInvalidatedEvent}，以及内核的
     * {@link PluginStateChangedEvent}——后者让「插件加载后立刻出现、卸载后立刻消失」变成白拿的行为，
     * 插件自己不用做任何事。
     * <p>
     * <b>回调可能在任意线程触发</b>（事件通道的订阅者线程）：监听器只应置一个标记，
     * 不得在此做收集或任何重活。
     *
     * @param listener 失效监听器，不可为 {@code null}
     */
    public void onInvalidated(final Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        subscriptions.add(events.subscribe(owner, UiInvalidatedEvent.class,
                event -> notifyInvalidated(listener)));
        subscriptions.add(events.subscribe(owner, PluginStateChangedEvent.class,
                event -> notifyInvalidated(listener)));
    }

    /**
     * 解除本类建立的全部订阅，幂等。
     */
    @Override
    public void close() {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.close();
            } catch (RuntimeException e) {
                LOG.warn("UI 贡献失效订阅解除失败：{}", e.getMessage());
            }
        }
        subscriptions.clear();
    }

    /**
     * 转发一次失效通知。
     * <p>
     * <b>收集期间照常转发，只多记一条告警</b>：丢弃看似能避开「收集 → 失效 → 收集」的自激循环，
     * 却会把「渲染线程刚开始收集、事件恰好在此时到达」这种<b>正常竞态</b>一并吞掉，
     * 而那是真的丢信息（插件内容会陈旧到下一个失效触发点）。
     * 自激循环的根因是处理器在收集过程中发布失效事件（契约明令禁止），因此这里只把它 loud 出来，
     * 让插件作者能看见，而不是用「静默丢弃」去掩盖——真正的不丢靠缓存侧的版本号。
     *
     * @param listener 失效监听器
     */
    private void notifyInvalidated(Runnable listener) {
        if (collecting) {
            LOG.warn("UI 贡献收集期间收到失效通知：处理器不应在收集过程中发布失效事件，否则每一帧都会重新收集");
        }
        try {
            listener.run();
        } catch (RuntimeException e) {
            LOG.warn("UI 贡献失效监听器失败：{}", e.getMessage());
        }
    }

    /**
     * 收集面板贡献。
     * <p>
     * <b>每个插件至多保留一块</b>：同一插件注册多个面板处理器时只取 {@code order} 最小的一个，
     * 其余记告警。注册表已按 {@code order} 升序给出绑定，因此「先到者胜」就是「order 最小者胜」。
     * 这条约束比「每个区域至多一块」更严（不区分区域）：面板是独占型资源，一个插件想同时占两块，
     * 用户就要用 {@code /ui} 逐个切换，而 {@code /ui} 的抽象是「区域 ← 插件」——
     * 同一个 pluginId 跨区域重复出现只会让清单读起来像两份配置。
     * <p>
     * 落位不在这里做：哪个插件的面板显示在哪个区域还要考虑用户的 {@code /ui} 选择，那是外壳的交互状态。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 面板列表（按 {@code order} 升序），无贡献时为空列表
     */
    private List<OwnedPanel> panels(String sessionId) {
        List<HandlerBinding<PanelContributionRequest, PanelContribution>> bindings =
                extensions.bindings(PanelContributionRequest.class, null);
        if (bindings.isEmpty()) {
            return Collections.emptyList();
        }
        // 同一个请求对象复用给全部处理器：载荷只有 sessionId，处理器只读
        PanelContributionRequest request = new PanelContributionRequest(sessionId);
        List<OwnedPanel> result = new ArrayList<OwnedPanel>(bindings.size());
        Set<String> owners = new HashSet<String>();
        for (HandlerBinding<PanelContributionRequest, PanelContribution> binding : bindings) {
            if (!owners.add(binding.getOwner())) {
                LOG.warn("插件 {} 注册了不止一个面板贡献，只保留 order 最小的那个", binding.getOwner());
                continue;
            }
            PanelContribution contribution = panelOf(binding, request);
            if (contribution != null) {
                result.add(new OwnedPanel(binding.getOwner(), contribution));
            }
        }
        return result;
    }

    /**
     * 执行单个面板贡献处理器并取出内容。
     *
     * @param binding 处理器绑定（含 owner，供告警归因）
     * @param request 贡献请求
     * @return 面板内容；无贡献或处理失败时返回 {@code null}
     */
    private PanelContribution panelOf(HandlerBinding<PanelContributionRequest, PanelContribution> binding,
                                      PanelContributionRequest request) {
        PanelContribution contribution;
        try {
            contribution = extensions.invoke(binding.getHandler(), request);
        } catch (RuntimeException e) {
            LOG.warn("插件 {} 的面板贡献失败，本次跳过：{}", binding.getOwner(), e.getMessage());
            return null;
        }
        return contribution == null || contribution.isEmpty() ? null : contribution;
    }

    /**
     * 收集状态栏片段。
     * <p>
     * 每个插件至多保留一段：同一插件注册多个贡献处理器时只取第一个，其余记告警——
     * 否则一个插件就能把状态栏塞满。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 片段列表，无贡献时为空列表
     */
    private List<String> statusFragments(String sessionId) {
        List<HandlerBinding<StatusLineContributionRequest, StatusLineContribution>> bindings =
                extensions.bindings(StatusLineContributionRequest.class, null);
        if (bindings.isEmpty()) {
            return Collections.emptyList();
        }
        // 同一个请求对象复用给全部处理器：载荷只有 sessionId，处理器只读
        StatusLineContributionRequest request = new StatusLineContributionRequest(sessionId);
        List<String> fragments = new ArrayList<String>(bindings.size());
        Set<String> owners = new HashSet<String>();
        for (HandlerBinding<StatusLineContributionRequest, StatusLineContribution> binding : bindings) {
            if (!owners.add(binding.getOwner())) {
                LOG.warn("插件 {} 注册了不止一个状态栏贡献，只保留第一个", binding.getOwner());
                continue;
            }
            String text = fragmentOf(binding, request);
            if (text != null) {
                fragments.add(text);
            }
        }
        return fragments;
    }

    /**
     * 执行单个状态栏贡献处理器并取出文本。
     *
     * @param binding 处理器绑定（含 owner，供告警归因）
     * @param request 贡献请求
     * @return 片段文本；无贡献或处理失败时返回 {@code null}
     */
    private String fragmentOf(HandlerBinding<StatusLineContributionRequest, StatusLineContribution> binding,
                              StatusLineContributionRequest request) {
        StatusLineContribution contribution;
        try {
            contribution = extensions.invoke(binding.getHandler(), request);
        } catch (RuntimeException e) {
            LOG.warn("插件 {} 的状态栏贡献失败，本次跳过：{}", binding.getOwner(), e.getMessage());
            return null;
        }
        if (contribution == null || contribution.isEmpty()) {
            return null;
        }
        String text = contribution.getText();
        return text == null || text.trim().isEmpty() ? null : text;
    }
}
