package zcd.jellyfish.infra.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.PluginStateChangedEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.extension.ShortcutBinding;
import zcd.jellyfish.api.extension.ShortcutContribution;
import zcd.jellyfish.api.extension.ShortcutContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolRenderHint;
import zcd.jellyfish.api.extension.ToolRenderHintRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link UiContributions} 的单元测试。
 * <p>
 * 用真实的注册表与事件通道（不 mock）：本类的职责就是「把注册表里的多个处理器有序收集起来、
 * 并对自己订阅的事件做出反应」，mock 掉这两者等于把被测逻辑挖空。
 * 事件通道是异步的，因此失效相关用例统一用轮询等待，而不是假定回调已经跑完。
 *
 * @author zcd
 */
@DisplayName("UI 贡献门面")
class UiContributionsTest {

    /** 等待异步通知的上限（毫秒），远超一次队列派发的实际耗时。 */
    private static final long AWAIT_TIMEOUT_MILLIS = 3000L;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略，注册与收集的落点。 */
    private ExtensionRegistry extensions;

    /** 事件通道，失效订阅的落点。 */
    private EventChannel events;

    /** 运行时信息持有者：请求里要带上外壳种类。 */
    private final RuntimeInfoHolder runtimeInfo = new RuntimeInfoHolder();

    /** 被测门面。 */
    private UiContributions contributions;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        events.start();
        contributions = new UiContributions(extensions, events, runtimeInfo, "tui");
    }

    @AfterEach
    void tearDown() {
        contributions.close();
        events.close();
    }

    @Test
    @DisplayName("没有插件表态时：提示表为空、键位表为空")
    void ui_depth_should_be_empty_when_no_handlers() {
        assertTrue(contributions.toolRenderHints().isEmpty());
        assertTrue(contributions.shortcuts().isEmpty());
    }

    @Test
    @DisplayName("工具行渲染提示按工具名索引，只保留表了态的")
    void toolRenderHints_should_index_by_tool_name() {
        registerTool("heartbeat");
        registerTool("read_file");
        extensions.handle("plugin-a", ToolRenderHintRequest.class, "heartbeat", null,
                request -> ToolRenderHint.of(null, null, Boolean.FALSE), RegisterOptions.DEFAULT);
        // 有条但三项都不表态：不进表——「表里没有」与「有条但不表态」对渲染是同一件事
        extensions.handle("plugin-a", ToolRenderHintRequest.class, "read_file", null,
                request -> ToolRenderHint.none(), RegisterOptions.DEFAULT);

        Map<String, ToolRenderHint> hints = contributions.toolRenderHints();

        assertEquals(Collections.singleton("heartbeat"), hints.keySet());
        assertEquals(Boolean.FALSE, hints.get("heartbeat").getShowArguments());
    }

    @Test
    @DisplayName("工具行渲染提示失败时按缺省渲染，不影响其它工具")
    void toolRenderHints_should_skip_failing_handler() {
        registerTool("heartbeat");
        registerTool("read_file");
        extensions.handle("plugin-a", ToolRenderHintRequest.class, "heartbeat", null,
                request -> {
                    throw new IllegalStateException("渲染提示炸了");
                }, RegisterOptions.DEFAULT);
        extensions.handle("plugin-b", ToolRenderHintRequest.class, "read_file", null,
                request -> ToolRenderHint.of(UiEmphasis.ACCENT, null, null), RegisterOptions.DEFAULT);

        Map<String, ToolRenderHint> hints = contributions.toolRenderHints();

        assertEquals(Collections.singleton("read_file"), hints.keySet());
    }

    @Test
    @DisplayName("键位按 order 升序交出，仲裁留给外壳")
    void shortcuts_should_be_ordered_by_order() {
        extensions.contribute("late", ShortcutContributionRequest.class, null,
                request -> ShortcutContribution.of(Collections.singletonList(
                        new ShortcutBinding("ctrl+b", "session", null))), RegisterOptions.order(10));
        extensions.contribute("early", ShortcutContributionRequest.class, null,
                request -> ShortcutContribution.of(Collections.singletonList(
                        new ShortcutBinding("ctrl+b", "todo", null))), RegisterOptions.order(-10));

        List<OwnedShortcut> shortcuts = contributions.shortcuts();

        assertEquals(2, shortcuts.size());
        assertEquals("early", shortcuts.get(0).getOwner());
        assertEquals("todo", shortcuts.get(0).getBinding().getCommandName());
    }

    @Test
    @DisplayName("内核保留键位被拒，插件其余键位照常生效")
    void shortcuts_should_reject_reserved_keys() {
        extensions.contribute("plugin-a", ShortcutContributionRequest.class, null,
                request -> ShortcutContribution.of(Arrays.asList(
                        new ShortcutBinding("ctrl+c", "quit", null),
                        new ShortcutBinding("ctrl+b", "todo", null))), RegisterOptions.DEFAULT);

        List<OwnedShortcut> shortcuts = contributions.shortcuts();

        assertEquals(1, shortcuts.size());
        assertEquals("ctrl+b", shortcuts.get(0).getBinding().getKey());
    }

    @Test
    @DisplayName("快捷键贡献失败只跳过它自己")
    void shortcuts_should_skip_failing_handler() {
        extensions.contribute("broken", ShortcutContributionRequest.class, null,
                request -> {
                    throw new IllegalStateException("炸了");
                }, RegisterOptions.DEFAULT);
        extensions.contribute("plugin-b", ShortcutContributionRequest.class, null,
                request -> ShortcutContribution.of(Collections.singletonList(
                        new ShortcutBinding("ctrl+b", "todo", null))), RegisterOptions.DEFAULT);

        assertEquals(1, contributions.shortcuts().size());
    }

    @Test
    @DisplayName("请求里带上了外壳信息与保留键位")
    void shortcuts_request_should_carry_shell_and_reserved_keys() {
        runtimeInfo.set(RuntimeInfo.tui(true));
        List<String> seen = new ArrayList<String>();
        extensions.contribute("plugin-a", ShortcutContributionRequest.class, null, request -> {
            seen.add(request.getShell().getShell() + "/" + request.getShell().isInteractive());
            seen.addAll(request.getReservedKeys());
            return ShortcutContribution.none();
        }, RegisterOptions.DEFAULT);

        contributions.shortcuts();

        assertTrue(seen.contains("TUI/true"), seen.toString());
        assertTrue(seen.contains("ctrl+c"), seen.toString());
    }

    /**
     * 注册一个最简工具描述符（渲染提示按工具名路由，得先有这个工具）。
     *
     * @param name 工具名
     */
    private void registerTool(String name) {
        extensions.handle("tools", ToolCallRequest.class, name, new ToolDescriptor(name, name),
                request -> new ToolCallResult(name, "ok"), RegisterOptions.DEFAULT);
    }

    @Test
    @DisplayName("没有人注册时返回空快照")
    void collect_should_returnEmpty_when_noHandlers() {
        assertTrue(contributions.collect("s-1").isEmpty());
    }

    @Test
    @DisplayName("按 order 升序收集片段，不是按注册顺序")
    void collect_should_orderFragments() {
        register("late", 10, "B");
        register("early", -10, "A");

        assertEquals(Arrays.asList("A", "B"),
                contributions.collect("s-1").getStatusFragments());
    }

    @Test
    @DisplayName("空贡献不占片段位：插件会被反复询问，只有真有事说时才该返回内容")
    void collect_should_skipEmptyContribution() {
        register("silent", 0, null);
        register("loud", 0, "X");

        assertEquals(Collections.singletonList("X"),
                contributions.collect("s-1").getStatusFragments());
    }

    @Test
    @DisplayName("单个插件注册多段时只保留第一段：否则一个插件就能塞满状态栏")
    void collect_should_keepFirstFragment_when_sameOwnerRegistersTwice() {
        register("greedy", 0, "first");
        register("greedy", 1, "second");

        assertEquals(Collections.singletonList("first"),
                contributions.collect("s-1").getStatusFragments());
    }

    @Test
    @DisplayName("一个插件抛错只跳过它自己，其余插件照常有内容")
    void collect_should_isolateFailingHandler() {
        registerFailing("broken");
        register("healthy", 1, "ok");

        assertEquals(Collections.singletonList("ok"),
                contributions.collect("s-1").getStatusFragments());
    }

    @Test
    @DisplayName("处理器为 null 时不当作内容，也不抛异常")
    void collect_should_ignoreNullContribution() {
        extensions.contribute("null-returning", StatusLineContributionRequest.class, null,
                request -> null, RegisterOptions.DEFAULT);

        assertTrue(contributions.collect("s-1").isEmpty());
    }

    @Test
    @DisplayName("请求带上会话标识：插件据此找回自己那份状态")
    void collect_should_passSessionId() {
        final StringBuilder seen = new StringBuilder();
        extensions.contribute("probe", StatusLineContributionRequest.class, null,
                request -> {
                    seen.append(request.getSessionId());
                    return StatusLineContribution.empty();
                }, RegisterOptions.DEFAULT);

        contributions.collect("s-42");

        assertEquals("s-42", seen.toString());
    }

    @Test
    @DisplayName("没有当前会话时也能收集，处理器拿到 null")
    void collect_should_tolerateNullSessionId() {
        final AtomicInteger calls = new AtomicInteger();
        extensions.contribute("probe", StatusLineContributionRequest.class, null,
                request -> {
                    calls.incrementAndGet();
                    return StatusLineContribution.empty();
                }, RegisterOptions.DEFAULT);

        contributions.collect(null);

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("面板按 order 升序收集，并带上 owner 供 /ui 归因")
    void collect_should_orderPanelsWithOwner() {
        registerPanel("late", 10, UiRegion.DOCK, "B");
        registerPanel("early", -10, UiRegion.LEFT, "A");

        List<OwnedPanel> panels = contributions.collect("s-1").getPanels();

        assertEquals(2, panels.size());
        assertEquals("early", panels.get(0).getOwner());
        assertEquals("late", panels.get(1).getOwner());
    }

    @Test
    @DisplayName("面板内容与建议区域原样透传：外壳可以忽略建议，但不能替插件改写")
    void collect_should_passPanelContent() {
        registerPanel("jellyfish-plugin-todo", 0, UiRegion.LEFT, "待办 2/5");

        OwnedPanel panel = contributions.collect("s-1").getPanels().get(0);

        assertEquals("待办", panel.getContribution().getTitle());
        assertEquals(UiRegion.LEFT, panel.getContribution().getPreferredRegion());
        assertEquals("待办 2/5", panel.getContribution().getLines().get(0).text());
    }

    @Test
    @DisplayName("同一插件注册多块面板时只保留第一块：否则一个插件就能把界面刷满")
    void collect_should_keepFirstPanel_when_sameOwnerRegistersTwice() {
        registerPanel("greedy", 0, UiRegion.DOCK, "first");
        registerPanel("greedy", 1, UiRegion.LEFT, "second");

        List<OwnedPanel> panels = contributions.collect("s-1").getPanels();

        assertEquals(1, panels.size());
        assertEquals("first", panels.get(0).getContribution().getLines().get(0).text());
    }

    @Test
    @DisplayName("空面板贡献不占位：面板要抢一整个区域，没内容就不该抢")
    void collect_should_skipEmptyPanel() {
        extensions.contribute("silent", PanelContributionRequest.class, null,
                request -> PanelContribution.empty(), RegisterOptions.DEFAULT);
        registerPanel("loud", 1, UiRegion.DOCK, "X");

        List<OwnedPanel> panels = contributions.collect("s-1").getPanels();

        assertEquals(1, panels.size());
        assertEquals("loud", panels.get(0).getOwner());
    }

    @Test
    @DisplayName("一个插件面板抛错只跳过它自己，其余插件正常出内容")
    void collect_should_isolateFailingPanelHandler() {
        ExtensionHandler<PanelContributionRequest, PanelContribution> failing = request -> {
            throw new IllegalStateException("boom");
        };
        extensions.contribute("broken", PanelContributionRequest.class, null, failing, RegisterOptions.DEFAULT);
        registerPanel("healthy", 1, UiRegion.DOCK, "ok");

        List<OwnedPanel> panels = contributions.collect("s-1").getPanels();

        assertEquals(1, panels.size());
        assertEquals("healthy", panels.get(0).getOwner());
    }

    @Test
    @DisplayName("面板请求也带上会话标识")
    void collect_should_passSessionIdToPanel() {
        final StringBuilder seen = new StringBuilder();
        extensions.contribute("probe", PanelContributionRequest.class, null,
                request -> {
                    seen.append(request.getSessionId());
                    return PanelContribution.empty();
                }, RegisterOptions.DEFAULT);

        contributions.collect("s-42");

        assertEquals("s-42", seen.toString());
    }

    @Test
    @DisplayName("片段与面板可以同时存在：两者是独立贡献通道")
    void collect_should_returnBothKindsOfContribution() {
        register("status", 0, "待办 2/5");
        registerPanel("panel", 1, UiRegion.DOCK, "明细");

        UiSnapshot snapshot = contributions.collect("s-1");

        assertEquals(Collections.singletonList("待办 2/5"), snapshot.getStatusFragments());
        assertEquals(1, snapshot.getPanels().size());
        assertTrue(!snapshot.isEmpty());
    }

    @Test
    @DisplayName("插件发布失效事件时通知监听器：这是插件主动刷新界面的唯一通道")
    void onInvalidated_should_notify_when_uiInvalidatedEventPublished() {
        AtomicInteger notifications = new AtomicInteger();
        contributions.onInvalidated(notifications::incrementAndGet);

        events.publish(new UiInvalidatedEvent());

        awaitTrue(notifications, 1);
    }

    @Test
    @DisplayName("插件加载/卸载也通知监听器：装上后出现、卸下后消失是白拿的行为")
    void onInvalidated_should_notify_when_pluginStateChangedPublished() {
        AtomicInteger notifications = new AtomicInteger();
        contributions.onInvalidated(notifications::incrementAndGet);

        events.publish(new PluginStateChangedEvent("jellyfish-plugin-todo", "STARTED"));

        awaitTrue(notifications, 1);
    }

    @Test
    @DisplayName("监听器抛错被隔离：一次坏回调不该打挂事件派发线程")
    void onInvalidated_should_isolateFailingListener() {
        AtomicInteger survived = new AtomicInteger();
        contributions.onInvalidated(() -> {
            throw new IllegalStateException("boom");
        });
        contributions.onInvalidated(survived::incrementAndGet);

        events.publish(new UiInvalidatedEvent());

        awaitTrue(survived, 1);
    }

    @Test
    @DisplayName("close 后再通知已建立的监听器：订阅真的被解除了")
    void close_should_stopNotifying() {
        AtomicInteger notifications = new AtomicInteger();
        contributions.onInvalidated(notifications::incrementAndGet);

        // 正向对照：先证明这条通路**能**触发。没有这一步，「关闭后没通知」在监听器
        // 压根没接上、或者事件压根没派发时也成立——那样这条防线只是「睡了 200 毫秒」
        events.publish(new UiInvalidatedEvent());
        awaitTrue(notifications, 1);

        // When：关闭之后再发一次
        contributions.close();
        int before = notifications.get();
        events.publish(new UiInvalidatedEvent());
        // 事件通道是异步的，因此要给「不该来的那一次」留出到达的时间；
        // 而上一条正向对照已经证明通路是活的，所以这个等待是在等一个**反例**
        sleep(200L);

        // Then
        assertEquals(before, notifications.get(), "关闭之后不该再通知已建立的监听器");
    }

    @Test
    @DisplayName("close 幂等：外壳可能在异常路径上也调一次")
    void close_should_beIdempotent() {
        contributions.onInvalidated(() -> {
            // 无需行为，只为让订阅真实存在
        });

        contributions.close();
        contributions.close();
    }

    @Test
    @DisplayName("来源标识不能为空白：它是反注册与告警归因的依据")
    void constructor_should_rejectBlankOwner() {
        assertThrows(JellyfishException.class, () -> new UiContributions(extensions, events, runtimeInfo, "  "));
        assertThrows(JellyfishException.class, () -> new UiContributions(extensions, events, runtimeInfo, null));
    }

    /**
     * 注册一段状态栏贡献。
     *
     * @param owner 来源标识
     * @param order 调用顺序
     * @param text  片段文本；{@code null} 表示返回空贡献
     */
    private void register(String owner, int order, final String text) {
        extensions.contribute(owner, StatusLineContributionRequest.class, null,
                request -> StatusLineContribution.of(text), RegisterOptions.order(order));
    }

    /**
     * 注册一块面板贡献。
     *
     * @param owner  来源标识
     * @param order  调用顺序
     * @param region 建议落位区域
     * @param body   正文第一行的内容
     */
    private void registerPanel(String owner, int order, UiRegion region, final String body) {
        extensions.contribute(owner, PanelContributionRequest.class, null,
                request -> PanelContribution.of("待办",
                        Collections.singletonList(UiLine.of(UiSegment.of(body))),
                        region),
                RegisterOptions.order(order));
    }

    /**
     * 注册一个总是抛错的状态栏贡献。
     *
     * @param owner 来源标识
     */
    private void registerFailing(String owner) {
        ExtensionHandler<StatusLineContributionRequest, StatusLineContribution> failing = request -> {
            throw new IllegalStateException("boom");
        };
        extensions.contribute(owner, StatusLineContributionRequest.class, null, failing, RegisterOptions.DEFAULT);
    }

    /**
     * 轮询等待计数器达到期望值。
     *
     * @param counter  计数器
     * @param expected 期望值
     */
    private static void awaitTrue(AtomicInteger counter, int expected) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (counter.get() >= expected) {
                return;
            }
            sleep(10L);
        }
        fail("等待异步通知超时，实际次数=" + counter.get());
    }

    /**
     * 静默睡眠。
     *
     * @param millis 毫秒数
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试被中断", e);
        }
    }
}
