package zcd.jellyfish.script.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.script.ScriptJson;

/**
 * 事件桥接：把内核事件推给脚本，并把脚本请求发布的事件代为发布。
 * <p>
 * 两个方向共用同一份白名单（{@link ScriptEventCatalog} / {@link ScriptEventFactory}），
 * 因此不存在「能收到但发不出去」这类被遗忘的组合。
 * <p>
 * <b>推送侧为什么必须有队列与线程</b>：事件由 {@code EventChannel} 的通知线程投递，
 * 而写子进程 stdin 是阻塞操作——对端（网关）读得慢或卡住时，直接写会把通知线程钉死在
 * 一次 socket 写上。通知线程被钉住的后果不是「事件晚了一点」，而是内核的全部通知都停摆
 * （指标、界面刷新、审计一起停）。因此这里只有入队，真正的写交给自己的一个线程。
 * <p>
 * <b>丢弃的三条理由都是「事件可丢」的不同侧面</b>：网关未运行、队列满、投影失败。
 * 三者都计数，让「脚本收不到事件」在台账里可见，而不是变成一句「事件桥接好像不太灵」。
 * <p>
 * <b>回声（脚本收到自己刚发布的事件）不在这里过滤</b>：推送是「一份事件、多脚本」的扇出，
 * 而扇出发生在网关里——只有它知道某个事件要发给哪几个脚本。在 Java 侧过滤只能选择
 * 「整条事件都不推」（会连累无关脚本），所以回声记忆放在网关，见 §6.6。
 *
 * @author zcd
 */
public final class ScriptEventBridge implements ScriptEventSink, AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptEventBridge.class);

    /** 推送队列容量：够吸收一次突发，又不至于让积压的事件比新事件还旧。 */
    private static final int QUEUE_CAPACITY = 256;

    /** 待推送队列：元素是已投影好的字段表。 */
    private final BlockingQueue<Map<String, Object>> queue =
            new ArrayBlockingQueue<Map<String, Object>>(QUEUE_CAPACITY);

    /** 被观察的事件类型对应的订阅句柄，关闭时逐条退订。 */
    private final List<Subscription> subscriptions = new ArrayList<Subscription>();

    /** 事件来源的显示名，只用于日志与线程名。 */
    private final String label;

    /** 推送目标（网关）。 */
    private final ScriptEventTarget target;

    /** 插件上下文：用来订阅与发布。 */
    private final PluginContext context;

    /** 额外收窄的事件白名单（{@code events.allow}）；为空表示不额外收窄。 */
    private final Set<String> allowed;

    /** 已推送条数。 */
    private final AtomicLong pushed = new AtomicLong();

    /** 因网关未运行而丢弃的条数。 */
    private final AtomicLong droppedOffline = new AtomicLong();

    /** 因队列满而丢弃的条数。 */
    private final AtomicLong droppedFull = new AtomicLong();

    /** 已受理的脚本发布次数。 */
    private final AtomicLong emitted = new AtomicLong();

    /** 被拒绝的脚本发布次数。 */
    private final AtomicLong rejected = new AtomicLong();

    /** 写线程。 */
    private Thread writer;

    /** 是否已关闭。 */
    private volatile boolean closed;

    /**
     * 构造事件桥接。
     *
     * @param context 插件上下文
     * @param label   事件来源显示名（如「Python 脚本」）
     * @param target  推送目标
     * @param allowed {@code events.allow} 声明的事件名；空表示不额外收窄
     */
    public ScriptEventBridge(PluginContext context, String label, ScriptEventTarget target, List<String> allowed) {
        this.context = context;
        this.label = label;
        this.target = target;
        Set<String> narrowing = new LinkedHashSet<String>();
        if (allowed != null) {
            for (String name : allowed) {
                if (ScriptEventCatalog.isObservable(name)) {
                    narrowing.add(name);
                } else {
                    // 配置里出现内核不认识的事件名：只告警不报错。它多半是笔误或旧配置，
                    // 而「启动失败」的代价远大于「少订阅一个事件」——后者在台账里看得见
                    LOG.warn("{} 的 events.allow 里有不可订阅的事件名，已忽略: {}", label, name);
                }
            }
        }
        this.allowed = narrowing;
    }

    /**
     * 开始订阅并启动推送线程。
     */
    public void start() {
        for (String name : ScriptEventCatalog.names()) {
            if (!allowed.isEmpty() && !allowed.contains(name)) {
                continue;
            }
            Class<? extends JellyfishEvent> type = ScriptEventCatalog.typeOf(name);
            if (type != null) {
                subscriptions.add(observe(type));
            }
        }
        writer = new Thread(this::drain, "jellyfish-script-events-" + label);
        writer.setDaemon(true);
        writer.start();
        LOG.info("{} 事件桥接已启动: 订阅 {} 个事件", label, Integer.valueOf(subscriptions.size()));
    }

    /**
     * 受理脚本的发布请求。
     * <p>
     * 返回值是刚发布出去的事件标识，网关要用它掐掉回声（脚本不必收到自己刚发的事件）；
     * 拒绝则抛异常，原因由调用点原样回给网关。
     *
     * @param scriptId  发起脚本
     * @param eventName 事件名
     * @param payload   载荷
     * @return 已发布事件的标识
     * @throws JellyfishException 事件不可发布或必填字段缺失时抛出
     */
    @Override
    public String accept(String scriptId, String eventName, JsonNode payload) {
        JellyfishEvent event = build(scriptId, eventName, payload);
        emitted.incrementAndGet();
        try {
            context.emit(event);
        } catch (RuntimeException error) {
            LOG.warn("{} 发布脚本 {} 的事件 {} 失败: {}", label, scriptId, eventName, error.toString());
            throw new JellyfishException("发布失败: " + error.getMessage(), error);
        }
        return event.getEventId();
    }

    /**
     * 构造（并记录拒绝）脚本发布的事件。
     *
     * @param scriptId  发起脚本
     * @param eventName 事件名
     * @param payload   载荷
     * @return 事件对象
     */
    private JellyfishEvent build(String scriptId, String eventName, JsonNode payload) {
        try {
            return ScriptEventFactory.build(scriptId, eventName, payload);
        } catch (JellyfishException error) {
            rejected.incrementAndGet();
            // 只记日志，不再补发一条告警事件：告警本身也是可订阅事件，
            // 而「脚本收到告警后又发一次非法事件」就成了一条跨进程的无限环。
            // 拒绝的现场（脚本 + 事件名 + 原因）在日志里齐全，够定位
            LOG.warn("{} 拒绝脚本 {} 发布事件 {}: {}", label, scriptId, eventName, error.getMessage());
            throw error;
        }
    }

    /**
     * 已推送条数。
     *
     * @return 条数
     */
    public long pushedCount() {
        return pushed.get();
    }

    /**
     * 丢弃条数合计。
     *
     * @return 条数
     */
    public long droppedCount() {
        return droppedOffline.get() + droppedFull.get();
    }

    /**
     * 因网关未运行而丢弃的条数。
     *
     * @return 条数
     */
    public long droppedOfflineCount() {
        return droppedOffline.get();
    }

    /**
     * 因队列满而丢弃的条数。
     *
     * @return 条数
     */
    public long droppedFullCount() {
        return droppedFull.get();
    }

    /**
     * 已受理发布次数。
     *
     * @return 次数
     */
    public long emittedCount() {
        return emitted.get();
    }

    /**
     * 被拒绝发布次数。
     *
     * @return 次数
     */
    public long rejectedCount() {
        return rejected.get();
    }

    /**
     * 当前生效的可观察事件名。
     *
     * @return 事件名集合
     */
    public Set<String> observableNames() {
        return allowed.isEmpty() ? ScriptEventCatalog.names()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(allowed));
    }

    /**
     * 一行摘要，供台账展示。
     *
     * @return 摘要文本
     */
    public String describe() {
        return "事件：推送 " + pushed.get() + "，丢弃 " + droppedCount()
                + "（网关未运行 " + droppedOffline.get() + "、队列满 " + droppedFull.get() + "）"
                + "；脚本发布 受理 " + emitted.get() + "、拒绝 " + rejected.get();
    }

    @Override
    public void close() {
        closed = true;
        for (Subscription subscription : subscriptions) {
            try {
                subscription.close();
            } catch (RuntimeException error) {
                LOG.warn("{} 退订事件失败: {}", label, error.toString());
            }
        }
        subscriptions.clear();
        Thread current = writer;
        writer = null;
        if (current != null) {
            current.interrupt();
            try {
                current.join(1000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        queue.clear();
    }

    /**
     * 订阅一个事件类型。
     *
     * @param type 事件类型
     * @param <E>  事件类型参数
     * @return 退订句柄
     */
    private <E extends JellyfishEvent> Subscription observe(Class<E> type) {
        return context.observe(type, new Consumer<E>() {
            @Override
            public void accept(E event) {
                enqueue(event);
            }
        });
    }

    /**
     * 投影并入队。本方法运行在事件通知线程上，因此**不允许**做任何可能阻塞的事。
     *
     * @param event 事件
     */
    private void enqueue(JellyfishEvent event) {
        if (closed) {
            return;
        }
        if (!target.isRunning()) {
            // 事件不拉起进程：网关的懒启动只由「调用」触发。让一个事件去 fork 解释器，
            // 等于把「事件到了」变成一次可能耗时几百毫秒、还可能失败的重操作，
            // 而且它发生在通知线程上；代价是「没有调用过的脚本收不到事件」——
            // 这与「事件可丢」是同一套取舍
            droppedOffline.incrementAndGet();
            return;
        }
        Map<String, Object> payload;
        try {
            payload = ScriptEventCatalog.project(event);
        } catch (RuntimeException error) {
            LOG.warn("{} 投影事件 {} 失败，已丢弃: {}", label, event.getClass().getSimpleName(),
                    error.toString());
            return;
        }
        if (!queue.offer(payload)) {
            droppedFull.incrementAndGet();
        }
    }

    /**
     * 推送线程：串行取出并发送。
     */
    private void drain() {
        while (!closed || !queue.isEmpty()) {
            Map<String, Object> payload;
            try {
                payload = queue.take();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!target.isRunning()) {
                droppedOffline.incrementAndGet();
                continue;
            }
            try {
                String name = String.valueOf(payload.get(ScriptEventCatalog.FIELD_EVENT));
                target.notifyEvent(name, ScriptJson.treeOf(payload));
                pushed.incrementAndGet();
            } catch (RuntimeException error) {
                // 发送失败只记日志：网关可能刚退出，而这个事件本来就允许丢。
                // 抛出去会让推送线程带着异常结束，此后所有事件都静默不再推送
                LOG.warn("{} 推送事件失败，已丢弃: {}", label, error.toString());
                droppedOffline.incrementAndGet();
            }
        }
    }
}
