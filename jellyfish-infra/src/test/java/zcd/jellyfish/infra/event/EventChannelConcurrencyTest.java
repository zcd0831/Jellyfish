package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventChannel} 的<b>并发</b>契约测试：通知是并发派发的，<b>只保证「最终送达」，不保证顺序</b>。
 * <p>
 * 为什么值得单独一组：这条性质是「可观测性、界面、审计不会因为一个慢订阅者一起停摆」的依据，
 * 但它此前只写在类注释里，没有任何用例守着。代价已经出现过一次——{@code MetricsSubscriberTest}
 * 里「等到总数到位再读分类计数」的写法在机器被压满时偶发失败（先发的通知还排在另一条线程上），
 * 而平时完全看不出来。<b>把这条性质钉住，后来的人才知道那些断言为什么必须各自等一遍。</b>
 * <p>
 * 这里用<b>真实的线程池</b>（不像同类里其它用例那样用「手动排水的执行器」——那按定义就是顺序的），
 * 并且靠闩而不是 {@code sleep} 来定序：慢订阅者「已经进到处理器里」这件事由它自己报告，
 * 于是「另一个通知是否已经完成」成为一个确定性的结论，而不是一个关于机器速度的赌注。
 *
 * @author zcd
 */
class EventChannelConcurrencyTest {

    /** 等待上限；正常机器上只是兜底，只有真的坏了才会触及。 */
    private static final long AWAIT_SECONDS = 5L;

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 真实线程池的通道。 */
    private final EventChannel channel = new EventChannel(EventChannelOptions.defaults(), registry);

    /**
     * 关闭通道，避免线程残留到其它用例。
     */
    @AfterEach
    void tearDown() {
        channel.close();
    }

    @Test
    void publish_should_not_stall_other_notifications_when_one_subscriber_blocks() throws InterruptedException {
        // Given：一个卡住的订阅者（A）与一个很快的订阅者（B）
        CountDownLatch blockedEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        channel.start();
        Subscription slow = channel.subscribe("slow", ConfigWarningEvent.class, event -> {
            blockedEntered.countDown();
            await(release);
        });
        Subscription fast = channel.subscribe("fast", SessionCreatedEvent.class, event -> fastDone.countDown());

        // When：先把 A 卡住，再发 B
        channel.publish(new ConfigWarningEvent("path", "message"));
        assertTrue(blockedEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "慢订阅者没有被派发到");

        // Then：B 必须能在 A 还卡着的时候走完——否则「一个慢订阅者拖住全部通知」就成立了
        channel.publish(new SessionCreatedEvent("coder", "s1"));
        assertTrue(fastDone.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                "另一个通知被卡住的订阅者拖住了：派发不是并发的");

        release.countDown();
        slow.close();
        fast.close();
    }

    @Test
    void publish_should_deliver_out_of_order_when_a_later_notification_is_faster() throws InterruptedException {
        // Given：先发的通知慢、后发的快
        List<String> completedOrder = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch laterDone = new CountDownLatch(1);
        channel.start();
        Subscription slow = channel.subscribe("slow", ConfigWarningEvent.class, event -> {
            await(release);
            completedOrder.add("first");
        });
        Subscription fast = channel.subscribe("fast", SessionCreatedEvent.class, event -> {
            completedOrder.add("second");
            laterDone.countDown();
        });

        // When
        channel.publish(new ConfigWarningEvent("path", "message"));
        channel.publish(new SessionCreatedEvent("coder", "s1"));

        // Then：**乱序是允许的**（后发先至），这正是「不能等一个总数就判断分类计数已落账」的原因
        assertTrue(laterDone.await(AWAIT_SECONDS, TimeUnit.SECONDS), "后发的通知没有完成");
        assertEquals(1, completedOrder.size());
        assertEquals("second", completedOrder.get(0));

        release.countDown();
        slow.close();
        fast.close();
    }

    /**
     * 等一个闩，中断时恢复中断位。
     *
     * @param latch 闩
     */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
