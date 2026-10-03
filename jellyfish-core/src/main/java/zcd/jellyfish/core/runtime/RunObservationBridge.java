package zcd.jellyfish.core.runtime;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.AgentRunProgressEvent;
import zcd.jellyfish.api.subagent.DelegationStatus;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;

/**
 * run 观测桥：把内核自持的 run 生命周期**翻成插件看得懂的通知事件**。
 * <p>
 * <b>为什么需要这一层</b>：run 的开始与结束发生在内核里，而插件侧完全看不到——
 * {@link RunEventBus} 携带的是 {@link AgentRunEvent}（纯内核类型），插件拿不到它。
 * 「任务列表里这一条现在谁在做、做完没有」这类需求因此断在中间。本类只做两件事：
 * 订阅内核总线、把两端（开始 / 结束）翻译成 {@link AgentRunProgressEvent} 发到通知通道上。
 * <p>
 * <b>为什么不把这一步做进调度器</b>：调度器该只关心「谁来跑、跑完了没有」。
 * 让它在关键路径上直接造对外事件，等于把「插件看得见什么」写进执行核心——那正是观测面该被隔离的理由。
 * 桥是只读订阅者，拿不到调度器的任何内部状态，也就无法反过来影响它。
 * <p>
 * <b>只桥两端，不桥每一步</b>：{@link AgentRunEvent.Kind#STEP} 在这里直接丢弃。
 * 它的量级远高于生命周期事件，而本事件的消费方（把 run 状态画到任务行上）用不到它。
 * 需要看过程的地方是面板与工具输出。
 * <p>
 * <b>可丢</b>：本类投递的是通知通道（异步、队列满即丢），因此消费方必须自愈
 * （见 {@link AgentRunProgressEvent} 的说明）。桥不做重试、不做缓冲：那不是它的职责，
 * 而一份「看起来可靠、实际仍会丢」的重试逻辑只会让消费方误以为可以不做自愈。
 * <p>
 * <b>失败不影响 run</b>：翻译或投递抛错都被内核总线的订阅者隔离机制挡住（记 WARN），
 * run 本身不会因为「界面收不到通知」而失败。
 * <p>
 * 线程安全：订阅在 {@link #start()} 里完成且幂等，投递发生在发布线程上（总线是同步扇出）。
 *
 * @author zcd
 */
@Singleton
public final class RunObservationBridge implements AutoCloseable {

    /** 内核 run 总线。 */
    private final RunEventBus bus;

    /** 通知发布入口（内核装配里通常是异步事件通道）。 */
    private final EventPublisher events;

    /** 退订句柄；未启动时为 {@code null}。 */
    private volatile Subscription subscription;

    /**
     * 构造桥。
     *
     * @param bus    内核 run 总线，不可为 {@code null}
     * @param events 通知发布入口，不可为 {@code null}
     */
    @Inject
    public RunObservationBridge(RunEventBus bus, EventPublisher events) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /**
     * 开始观测：订阅内核 run 总线。
     * <p>
     * <b>幂等</b>：重复调用不会重复订阅——重复订阅会让同一条 run 事件投递多份，
     * 而消费方无法分辨那是「两次开始」还是「一次开始被送了两遍」。
     */
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = bus.subscribe(this::republish);
    }

    /**
     * 停止观测并退订。
     * <p>
     * 幂等；停止后不再投递任何通知。内核停机时调用，避免退订句柄悬在总线上。
     */
    @Override
    public synchronized void close() {
        if (subscription == null) {
            return;
        }
        subscription.close();
        subscription = null;
    }

    /**
     * 把一条内核 run 事件翻译成通知事件。
     * <p>
     * {@link AgentRunEvent.Kind#STEP} 直接丢弃（见类文档）；其余两端逐字段搬运，
     * 终态分类由 {@link #outcomeOf(AgentRunStatus)} 归一。
     *
     * @param event 内核 run 事件，不可为 {@code null}
     */
    private void republish(AgentRunEvent event) {
        if (event.getKind() == AgentRunEvent.Kind.STEP) {
            return;
        }
        AgentRunSnapshot run = event.getRun();
        if (run == null) {
            // 理论上不会发生；宁可漏一条通知，也不要在通知路径上抛错惊动总线
            return;
        }
        if (event.getKind() == AgentRunEvent.Kind.STARTED) {
            events.publish(AgentRunProgressEvent.started(run.getRunId(), run.getParentRunId(),
                    run.getRootRunId(), run.getParentSessionId(), run.getAgentId()));
            return;
        }
        events.publish(AgentRunProgressEvent.finished(run.getRunId(), run.getParentRunId(),
                run.getRootRunId(), run.getParentSessionId(), run.getAgentId(),
                outcomeOf(run.getStatus()), run.getRounds(), run.getTotalTokens()));
    }

    /**
     * 把内核的 run 终态映射成插件侧的结局分类。
     * <p>
     * 两套词表各司其职：内核那份描述**执行状态机**（还包含「排队中」「等孩子」这些非终态），
     * 插件这份只描述**结局**。映射表就是两者之间的唯一约定，因此它必须显式、可读、可测。
     * <p>
     * 兜底走 {@link DelegationStatus#FAILED}：内核的 {@code finish} 已经保证终态只能是下面这五个，
     * 真出现别的值说明有不变量被破坏——那时让消费方看到「已结束但不成功」，比让它看到一个
     * 需要额外分支处理的陌生分类要好。
     *
     * @param status 内核 run 终态，不可为 {@code null}
     * @return 插件侧结局分类，保证非 {@code null}
     */
    private static DelegationStatus outcomeOf(AgentRunStatus status) {
        switch (status) {
            case DONE:
                return DelegationStatus.COMPLETED;
            case TRUNCATED:
                return DelegationStatus.TRUNCATED;
            case CANCELLED:
                return DelegationStatus.CANCELLED;
            case BLOCKED:
                // 被拦下 = 从未开始，与委派侧的 REJECTED 是同一件事
                return DelegationStatus.REJECTED;
            default:
                return DelegationStatus.FAILED;
        }
    }
}
