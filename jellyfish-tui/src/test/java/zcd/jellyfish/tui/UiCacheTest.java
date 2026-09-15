package zcd.jellyfish.tui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.ui.UiContributions;
import zcd.jellyfish.infra.ui.UiSnapshot;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiCache} 的单元测试。
 * <p>
 * 这里的核心不是「缓存能不能存住」，而是<b>什么时候必须重新收集</b>——本方案的调用模型是
 * 「失效时收集」，因此「该收集时没收集」会让插件内容永久陈旧，而「不该收集时收集了」会让
 * 空闲时也在反复调用插件处理器。两者都是本类要守住的边界。
 * <p>
 * 用真实的注册表与事件通道（不 mock）：{@link UiContributions} 的行为就是「调用几次收集」，
 * 用计数器处理器直接数出来最直观。
 *
 * @author zcd
 */
@DisplayName("插件 UI 贡献缓存")
class UiCacheTest {

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    /** 贡献门面。 */
    private UiContributions contributions;

    /** 被测缓存。 */
    private UiCache cache;

    /** 收集次数计数。 */
    private AtomicInteger collects;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        contributions = new UiContributions(extensions, events, "tui");
        cache = new UiCache(contributions);
        collects = new AtomicInteger();
        extensions.contribute("probe", StatusLineContributionRequest.class, null,
                request -> {
                    collects.incrementAndGet();
                    return StatusLineContribution.of("probe");
                }, RegisterOptions.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        contributions.close();
        events.close();
    }

    @Test
    @DisplayName("首次取快照必须收集：否则首帧上什么都看不到")
    void snapshot_should_collectOnFirstUse() {
        UiSnapshot snapshot = cache.snapshot("s-1");

        assertEquals(1, collects.get());
        assertEquals(1, snapshot.getStatusFragments().size());
    }

    @Test
    @DisplayName("没有失效时反复取快照命中同一份缓存，空闲时零插件调用")
    void snapshot_should_reuseCache_when_nothingChanged() {
        UiSnapshot first = cache.snapshot("s-1");
        UiSnapshot second = cache.snapshot("s-1");

        assertEquals(1, collects.get());
        assertSame(first, second);
    }

    @Test
    @DisplayName("置失效后重新收集")
    void snapshot_should_recollect_when_invalidated() {
        cache.snapshot("s-1");

        cache.invalidate();
        cache.snapshot("s-1");

        assertEquals(2, collects.get());
    }

    @Test
    @DisplayName("会话变了必须重新收集：插件贡献是按会话给的")
    void snapshot_should_recollect_when_sessionChanged() {
        cache.snapshot("s-1");

        cache.snapshot("s-2");

        assertEquals(2, collects.get());
    }

    @Test
    @DisplayName("会话没变时不因「重复传同一个 id」而重新收集")
    void snapshot_should_notRecollect_when_sessionUnchanged() {
        cache.snapshot("s-1");
        cache.snapshot("s-1");
        cache.snapshot("s-1");

        assertEquals(1, collects.get());
    }

    @Test
    @DisplayName("连续置失效只多收集一次：失效是廉价标记，不是计数器")
    void snapshot_should_collectOnce_when_invalidatedRepeatedly() {
        cache.snapshot("s-1");

        cache.invalidate();
        cache.invalidate();
        cache.invalidate();
        cache.snapshot("s-1");

        assertEquals(2, collects.get());
    }

    @Test
    @DisplayName("收集期间发生的失效不会丢：收集结束后版本对不上，下一帧会再收集")
    void snapshot_should_recollect_when_invalidationArrivedDuringCollect() {
        // 在收集过程中置失效，确定性地复现「渲染线程刚刚开始收集、失效恰好到达」这个竞态
        extensions.contribute("racing", StatusLineContributionRequest.class, null,
                request -> {
                    collects.incrementAndGet();
                    cache.invalidate();
                    return StatusLineContribution.of("racing");
                }, RegisterOptions.DEFAULT);

        cache.snapshot("s-1");
        int afterFirstCollect = collects.get();
        cache.snapshot("s-1");

        assertTrue(collects.get() > afterFirstCollect,
                "收集期间到达的失效被吞掉了：实际收集次数仍为 " + afterFirstCollect);
    }

    @Test
    @DisplayName("没有当前会话（null）也能取快照，且不会因为「null 等于 null」而漏掉首次收集")
    void snapshot_should_collectOnFirstUse_when_sessionIdIsNull() {
        UiSnapshot snapshot = cache.snapshot(null);

        assertEquals(1, collects.get());
        assertFalse(snapshot.isEmpty());
    }
}
