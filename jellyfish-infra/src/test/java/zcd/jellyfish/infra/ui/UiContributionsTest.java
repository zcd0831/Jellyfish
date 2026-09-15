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
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.Collections;
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

    /** 被测门面。 */
    private UiContributions contributions;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        events.start();
        contributions = new UiContributions(extensions, events, "tui");
    }

    @AfterEach
    void tearDown() {
        contributions.close();
        events.close();
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

        events.publish(new PluginStateChangedEvent("jellyfish-todo", "STARTED"));

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
        contributions.close();

        events.publish(new UiInvalidatedEvent());
        // 事件通道是异步的，等一小会儿再断言「始终没通知」
        sleep(200L);

        assertEquals(0, notifications.get());
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
        assertThrows(JellyfishException.class, () -> new UiContributions(extensions, events, "  "));
        assertThrows(JellyfishException.class, () -> new UiContributions(extensions, events, null));
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
