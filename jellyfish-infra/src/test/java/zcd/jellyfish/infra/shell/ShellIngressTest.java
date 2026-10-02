package zcd.jellyfish.infra.shell;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ShellContribution;
import zcd.jellyfish.api.extension.ShellContributionStatus;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.infra.metrics.MetricNames;
import zcd.jellyfish.infra.metrics.MetricsRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellIngress} 的单元测试：存、限、合并、取、丢，以及那条硬约束。
 *
 * @author zcd
 */
@DisplayName("外壳贡献信箱")
class ShellIngressTest {

    /** 指标，用于核对账目。 */
    private MetricsRegistry metrics;

    /** 被测对象。 */
    private ShellIngress ingress;

    @BeforeEach
    void setUp() {
        metrics = new MetricsRegistry();
        ingress = new ShellIngress(metrics, 4);
    }

    @Test
    @DisplayName("入队后可被整批取走，且顺序与入队一致")
    void drain_should_returnEntriesInInsertionOrder() {
        ingress.present("plugin-a", notice("a1"));
        ingress.present("plugin-a", notice("a2"));

        List<ShellIngress.Entry> entries = ingress.drain();

        assertEquals(2, entries.size());
        assertEquals("a1", text(entries.get(0)));
        assertEquals("a2", text(entries.get(1)));
        assertTrue(entries.get(0).getSequence() < entries.get(1).getSequence());
    }

    @Test
    @DisplayName("取走即清空：第二次取拿不到任何东西")
    void drain_should_clearTheMailbox() {
        ingress.present("plugin-a", notice("a1"));

        assertEquals(1, ingress.drain().size());
        assertEquals(0, ingress.drain().size());
    }

    @Test
    @DisplayName("同 owner + 同 key 的后到者原地替换先到者，位置不变")
    void present_should_coalesceByOwnerAndKey() {
        ingress.present("plugin-a", keyed("a1"));
        ingress.present("plugin-b", keyed("b1"));
        ShellContributionStatus status = ingress.present("plugin-a",
                ShellContribution.notice(ShellContribution.Scope.SHELL, null, "progress",
                        ShellContribution.Severity.INFO, lines("a2")));

        assertEquals(ShellContributionStatus.COALESCED, status);
        List<ShellIngress.Entry> entries = ingress.drain();
        // 合并是原地更新：进度类通知不该因为更新一次就被顶到队尾去
        assertEquals(2, entries.size());
        assertEquals("a2", text(entries.get(0)));
        assertEquals("b1", text(entries.get(1)));
    }

    @Test
    @DisplayName("同 key 但不同 owner 不合并：命名空间是隔离的")
    void present_should_notCoalesceAcrossOwners() {
        ingress.present("plugin-a", ShellContribution.notice(ShellContribution.Scope.SHELL, null, "progress",
                ShellContribution.Severity.INFO, lines("a")));
        ShellContributionStatus status = ingress.present("plugin-b",
                ShellContribution.notice(ShellContribution.Scope.SHELL, null, "progress",
                        ShellContribution.Severity.INFO, lines("b")));

        assertEquals(ShellContributionStatus.ACCEPTED, status);
        assertEquals(2, ingress.drain().size());
    }

    @Test
    @DisplayName("无 key 的通知不合并：两条内容不同的通知本就是两条")
    void present_should_notCoalesceWithoutKey() {
        ingress.present("plugin-a", notice("a1"));
        ShellContributionStatus status = ingress.present("plugin-a", notice("a2"));

        assertEquals(ShellContributionStatus.ACCEPTED, status);
        assertEquals(2, ingress.drain().size());
    }

    @Test
    @DisplayName("失效提示不带 key，因此永不合并")
    void present_should_notCoalesceInvalidatedEvents() {
        ingress.present("plugin-a", ShellContribution.invalidated(ShellContribution.Scope.SHELL, null, null));
        ShellContributionStatus status = ingress.present("plugin-a",
                ShellContribution.invalidated(ShellContribution.Scope.SHELL, null, null));

        assertEquals(ShellContributionStatus.ACCEPTED, status);
        assertEquals(2, ingress.drain().size());
    }

    @Test
    @DisplayName("容量打满后丢最新一条并回报：不阻塞、不踢掉已排队的那几条")
    void present_should_dropNewestWhenFull() {
        for (int i = 0; i < 4; i++) {
            assertEquals(ShellContributionStatus.ACCEPTED, ingress.present("plugin-a", notice("n" + i)));
        }

        assertEquals(ShellContributionStatus.DROPPED_QUEUE_FULL, ingress.present("plugin-a", notice("n4")));

        List<ShellIngress.Entry> entries = ingress.drain();
        assertEquals(4, entries.size());
        /// 丢的是最新那条：界面因此停在「更近的状态」上，而不是卡在中间态
        assertEquals("n0", text(entries.get(0)));
        assertEquals("n3", text(entries.get(3)));
    }

    @Test
    @DisplayName("一个 owner 的洪水只填满它自己的桶")
    void present_should_isolateCapacityPerOwner() {
        for (int i = 0; i < 10; i++) {
            ingress.present("flood", notice("f" + i));
        }

        assertEquals(ShellContributionStatus.ACCEPTED, ingress.present("quiet", notice("q")));

        List<ShellIngress.Entry> entries = ingress.drain();
        assertEquals(5, entries.size());
        assertEquals("quiet", entries.get(entries.size() - 1).getOwner());
    }

    @Test
    @DisplayName("空 owner 落进同一个匿名桶，不会因为 null 崩掉")
    void present_should_tolerateBlankOwner() {
        assertEquals(ShellContributionStatus.ACCEPTED, ingress.present(null, notice("x")));
        assertEquals(ShellContributionStatus.ACCEPTED, ingress.present("  ", notice("y")));

        List<ShellIngress.Entry> entries = ingress.drain();
        assertEquals(2, entries.size());
        assertEquals("", entries.get(0).getOwner());
    }

    @Test
    @DisplayName("重置按命名空间前缀丢弃：根 owner 与 owner::* 一起清掉")
    void reset_should_dropRootAndChildNamespaces() {
        ingress.present("plugin-a", notice("root"));
        ingress.present("plugin-a::child", notice("child"));
        ingress.present("plugin-ab", notice("sibling"));

        assertEquals(2, ingress.reset("plugin-a"));

        List<ShellIngress.Entry> entries = ingress.drain();
        assertEquals(1, entries.size());
        // 前缀匹配必须带上分隔符，否则 plugin-a 会把 plugin-ab 也一起端掉
        assertEquals("plugin-ab", entries.get(0).getOwner());
    }

    @Test
    @DisplayName("重置后该 owner 的容量重新可用：桶是空的")
    void reset_should_freeTheBucket() {
        for (int i = 0; i < 4; i++) {
            ingress.present("plugin-a", notice("n" + i));
        }
        assertEquals(ShellContributionStatus.DROPPED_QUEUE_FULL, ingress.present("plugin-a", notice("x")));

        ingress.reset("plugin-a");

        assertEquals(ShellContributionStatus.ACCEPTED, ingress.present("plugin-a", notice("y")));
    }

    @Test
    @DisplayName("重置空 owner 不做任何事")
    void reset_should_ignoreNullOwner() {
        ingress.present("plugin-a", notice("a"));

        assertEquals(0, ingress.reset(null));
        assertEquals(1, ingress.drain().size());
    }

    @Test
    @DisplayName("取走之后不再参与合并：已交付的条目回不来")
    void coalesce_should_onlySeeUndeliveredEntries() {
        ingress.present("plugin-a", ShellContribution.notice(ShellContribution.Scope.SHELL, null, "k",
                ShellContribution.Severity.INFO, lines("first")));
        ingress.drain();

        ShellContributionStatus status = ingress.present("plugin-a",
                ShellContribution.notice(ShellContribution.Scope.SHELL, null, "k",
                        ShellContribution.Severity.INFO, lines("second")));

        assertEquals(ShellContributionStatus.ACCEPTED, status);
    }

    @Test
    @DisplayName("合并过的条目也能被重置清掉：key 索引与队列不能失配")
    void reset_should_clearCoalescedKeyIndex() {
        ingress.present("plugin-a", ShellContribution.notice(ShellContribution.Scope.SHELL, null, "k",
                ShellContribution.Severity.INFO, lines("first")));
        ingress.present("plugin-a", ShellContribution.notice(ShellContribution.Scope.SHELL, null, "k",
                ShellContribution.Severity.INFO, lines("second")));

        ingress.reset("plugin-a");

        // 索引若没清，这里会被误判成 COALESCED 并原地替换一个早已不存在的条目
        assertEquals(ShellContributionStatus.ACCEPTED, ingress.present("plugin-a",
                ShellContribution.notice(ShellContribution.Scope.SHELL, null, "k",
                        ShellContribution.Severity.INFO, lines("third"))));
        assertEquals(1, ingress.drain().size());
    }

    @Test
    @DisplayName("三个计数各自记账：accepted + coalesced + dropped 就是全部投递")
    void counters_should_addUp() {
        ingress.present("plugin-a", ShellContribution.notice(ShellContribution.Scope.SHELL, null, "k",
                ShellContribution.Severity.INFO, lines("first")));
        ingress.present("plugin-a", ShellContribution.notice(ShellContribution.Scope.SHELL, null, "k",
                ShellContribution.Severity.INFO, lines("second")));
        for (int i = 0; i < 10; i++) {
            ingress.present("plugin-a", notice("n" + i));
        }
        ingress.recordRejection("plugin-a", ShellContributionStatus.DROPPED_NO_SESSION);

        // 2 条带 key 的投递里 1 条被合并，1 条入队；随后 10 条无 key 的投递里 3 条还能挤进 4 格容量
        assertEquals(1, counter(MetricNames.PLUGIN_SHELL_CONTRIBUTION_COALESCED));
        assertEquals(4, counter(MetricNames.PLUGIN_SHELL_CONTRIBUTION_ACCEPTED));
        assertEquals(8, counter(MetricNames.PLUGIN_SHELL_CONTRIBUTION_DROPPED));
        assertEquals(13, counter(MetricNames.PLUGIN_SHELL_CONTRIBUTION_ACCEPTED)
                + counter(MetricNames.PLUGIN_SHELL_CONTRIBUTION_COALESCED)
                + counter(MetricNames.PLUGIN_SHELL_CONTRIBUTION_DROPPED));
    }

    @Test
    @DisplayName("no-session / no-renderer 的拒绝原样回报，调用方不必自己再判一次")
    void recordRejection_should_returnStatusAsIs() {
        assertEquals(ShellContributionStatus.DROPPED_NO_SESSION,
                ingress.recordRejection("plugin-a", ShellContributionStatus.DROPPED_NO_SESSION));
        assertEquals(ShellContributionStatus.DROPPED_NO_RENDERER,
                ingress.recordRejection("plugin-a", ShellContributionStatus.DROPPED_NO_RENDERER));
    }

    @Test
    @DisplayName("容量必须为正数：0 会让每一条都被丢掉，那是配置错误而不是运行条件")
    void constructor_should_rejectNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ShellIngress(metrics, 0));
        assertThrows(NullPointerException.class, () -> new ShellIngress(null, 4));
    }

    @Test
    @DisplayName("并发投递与取走之后账目仍然对得上")
    void concurrentPresentAndDrain_should_keepTheBooksBalanced() throws Exception {
        final int threads = 4;
        final int perThread = 25;
        final int capacity = 8;
        final ShellIngress concurrent = new ShellIngress(metrics, capacity);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger delivered = new AtomicInteger();
        final AtomicInteger dropped = new AtomicInteger();
        final ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < perThread; i++) {
                        // 无 key：不做合并，账目因此只剩「入队」与「丢弃」两种
                        ShellContributionStatus status = concurrent.present("p" + id, notice("n" + i));
                        if (status == ShellContributionStatus.DROPPED_QUEUE_FULL) {
                            dropped.incrementAndGet();
                        }
                    }
                });
            }
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < 200; i++) {
                    delivered.addAndGet(concurrent.drain().size());
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            delivered.addAndGet(concurrent.drain().size());
        } finally {
            pool.shutdownNow();
        }

        long accepted = counter(MetricNames.PLUGIN_SHELL_CONTRIBUTION_ACCEPTED);
        assertEquals(threads * perThread, accepted + dropped.get());
        assertEquals(accepted, delivered.get());
        // 丢的只能来自「满了」：没有别的分支会在这条路径上丢东西
        assertEquals(dropped.get(), counter(MetricNames.PLUGIN_SHELL_CONTRIBUTION_DROPPED));
    }

    @Test
    @DisplayName("投递不阻塞调用方：即使队列已满也立刻返回")
    void present_should_neverBlock() {
        for (int i = 0; i < 4; i++) {
            ingress.present("plugin-a", notice("n" + i));
        }

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            ingress.present("plugin-a", notice("x"));
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis < 1000, "满队列下的投递不该阻塞，实测 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("硬约束：本类不持有任何创建会话或起回合的协作者")
    void shellIngress_should_haveNoSessionOrTurnCapability() {
        // 「插件不能新开会话、不能起回合」这条承诺必须落在类型上：本类只接贡献，
        // 拿不到 SessionManager 也就没有 create/switchTo，拿不到 AgentHarness 也就没有 chat
        for (Field field : ShellIngress.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(type.contains("SessionManager") || type.contains("AgentHarness")
                            || type.contains("ActionQueue") || type.contains("ConversationService"),
                    "字段不该持有会话 / 回合能力：" + field);
        }
        for (Constructor<?> constructor : ShellIngress.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(parameter.getName().contains("SessionManager")
                                || parameter.getName().contains("AgentHarness"),
                        "构造器不该注入会话 / 回合能力：" + constructor);
            }
            assertTrue(Modifier.isPrivate(constructor.getModifiers())
                            || constructor.getParameterCount() <= 2,
                    "意料之外的构造器：" + constructor);
        }
    }

    /**
     * 造一条带 key 的通知。
     *
     * @param text 文本
     * @return 贡献
     */
    private static ShellContribution keyed(String text) {
        return ShellContribution.notice(ShellContribution.Scope.SHELL, null, "progress",
                ShellContribution.Severity.INFO, lines(text));
    }

    /**
     * 造一条无 key 的通知。
     *
     * @param text 文本
     * @return 贡献
     */
    private static ShellContribution notice(String text) {
        return ShellContribution.notice(ShellContribution.Scope.SHELL, null, null,
                ShellContribution.Severity.INFO, lines(text));
    }

    /**
     * 造一个内容行列表。
     *
     * @param texts 行文本
     * @return 行列表
     */
    private static List<UiLine> lines(String... texts) {
        List<UiLine> lines = new ArrayList<UiLine>();
        for (String text : texts) {
            lines.add(UiLine.of(text));
        }
        return lines;
    }

    /**
     * 取条目的首行文本。
     *
     * @param entry 条目
     * @return 文本
     */
    private static String text(ShellIngress.Entry entry) {
        return entry.getContribution().getLines().get(0).text();
    }

    /**
     * 读一个计数。
     *
     * @param name 指标名
     * @return 计数值
     */
    private long counter(String name) {
        Long value = metrics.snapshot().getCounters().get(name);
        return value == null ? 0 : value;
    }

    /**
     * 等待发令枪。
     *
     * @param latch 发令枪
     */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("Arrays.asList 造的行列表也不会被就地改写")
    void present_should_notMutateCallerLines() {
        List<UiLine> source = Arrays.asList(UiLine.of("a"));
        ingress.present("plugin-a", ShellContribution.notice(ShellContribution.Scope.SHELL, null, null,
                ShellContribution.Severity.INFO, source));

        assertEquals(1, ingress.drain().get(0).getContribution().getLines().size());
    }
}
