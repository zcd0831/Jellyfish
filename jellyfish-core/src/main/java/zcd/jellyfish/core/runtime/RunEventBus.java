package zcd.jellyfish.core.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.Subscription;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * agent run 的可靠事件总线：内核发布 run 生命周期事件，外壳与消费方订阅。
 * <p>
 * <b>为什么运行时自持一条总线，而不是挂到 {@code ShellStreams} 上</b>：run 生命周期在
 * {@code core.runtime}，而 {@code core.conversation → core → core.runtime}。把发布点接到外壳通道，
 * 要么做依赖倒置加 Dagger 绑定，要么从 {@code core.subagent} 转发；自持一条总线的依赖方向是
 * 「外壳 → 运行时」，天然无环、无需额外绑定，也与 P3 的「agent 运行时事件」同源。
 * <p>
 * <b>与 {@code ShellStreams} 可靠 lane 同一套契约</b>：同步扇出、不排队、不丢弃、
 * 单个订阅者抛错只记 WARN 并跳过。因此订阅者必须<b>快且线程安全</b>——它挡在 run 的推进之间，
 * 做 I/O 就会拖住整个 run。
 * <p>
 * <b>插件不走这条总线</b>：插件拿不到内核类型（api 边界）；它们要观测 run 起止应走
 * {@code EventChannel} 上的通知事件（异步、可丢）。两者是不同边界，不互相替代。
 * <p>
 * <b>输出流不在这里</b>：每段工具输出量大致必须可丢，待真需要时另设可丢通道（见 P1 分册 D-P1-5）。
 * <p>
 * 线程安全：订阅表用 {@link CopyOnWriteArrayList}。
 *
 * @author zcd
 */
@Singleton
public final class RunEventBus {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(RunEventBus.class);

    /** 订阅者。 */
    private final CopyOnWriteArrayList<Consumer<AgentRunEvent>> subscribers =
            new CopyOnWriteArrayList<Consumer<AgentRunEvent>>();

    /**
     * 构造空总线。
     */
    @Inject
    public RunEventBus() {
    }

    /**
     * 订阅全部 run 事件。
     *
     * @param subscriber 订阅者，不可为 {@code null}
     * @return 退订句柄，保证非 {@code null}
     */
    public Subscription subscribe(final Consumer<AgentRunEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        subscribers.add(subscriber);
        return new Subscription() {
            @Override
            public void close() {
                subscribers.remove(subscriber);
            }
        };
    }

    /**
     * 发布一条 run 事件：同步扇出给全部订阅者。
     * <p>
     * 单个订阅者抛错被隔离并记 WARN——它不该让别的订阅者收不到，更不该让 run 本身失败。
     *
     * @param event 事件，不可为 {@code null}
     */
    public void publish(AgentRunEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (subscribers.isEmpty()) {
            return;
        }
        // 取快照再遍历：订阅表是写时复制的，但遍历中仍可能有人退订
        List<Consumer<AgentRunEvent>> snapshot =
                new ArrayList<Consumer<AgentRunEvent>>(subscribers);
        for (Consumer<AgentRunEvent> subscriber : snapshot) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException e) {
                LOG.warn("run 事件订阅者抛错，已跳过本条（kind={} runId={}）：{}",
                        event.getKind(), event.getRunId(), e.getMessage());
            }
        }
    }
}
