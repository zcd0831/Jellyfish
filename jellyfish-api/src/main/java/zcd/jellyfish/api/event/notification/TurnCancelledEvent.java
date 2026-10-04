package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 回合取消事件：一个在途回合被用户或客户端主动打断时广播。
 * <p>
 * <b>为什么需要它</b>：此前「回合被打断」这件事在内核里是一条只走异常与日志的路径——取消是协作式的，
 * 循环在下一个检查点退出、终态回调收敛成 {@code onCancelled}，而那条回调只走外壳的可靠 lane
 * （{@code ShellTurnEvent.CANCELLED}）。插件<b>看不到可靠的回合通道</b>，于是它只能靠事件计数去猜
 * 「用户是不是又打断了」，而通知目录里在此之前没有任何一条与取消有关。本事件把那件事放到可订阅的通道上。
 * <p>
 * <b>它只报「取消」，不报「回合怎么结束的」</b>：正常收敛、被钩子拦下、失败都不发本事件。
 * 那三者的收尾各有其位（{@code onComplete} / {@code onBlocked} / {@code onError}），
 * 合成一个「回合终态」事件会让消费方还得自己去分辨是哪一种，而它们要处理的事情完全不同。
 * <p>
 * <b>嵌套回合（子代理）不发</b>：子代理的取消由父回合的取消级联下来，报出来只会让「你打断了几次」
 * 把一次打断记成 N 次。本事件只由顶层回合的取消入口（{@code TurnRegistry.cancel}）发出。
 * <p>
 * <b>它可能被丢掉</b>：走的是异步通知通道（有界队列、满则丢弃），因此消费方不该把它当成精确计数——
 * 与 {@link AgentRunProgressEvent} 同口径。需要「一次不落」的地方是外壳的可靠 lane，不是这里。
 * <p>
 * <b>同一次取消不会被上报两遍</b>：取消入口是幂等的，重复按取消键（或在收敛过程中再次请求取消）
 * 不会再发一条同回合的事件，因此消费方可以直接累加，不必自己维护去重表。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnCancelledEvent extends AbstractJellyfishEvent {

    /** 被取消的回合标识。 */
    private final String turnId;

    /**
     * 构造回合取消事件。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param turnId    被取消的回合标识，不可为空白
     * @throws JellyfishException 回合标识为空白时抛出
     */
    public TurnCancelledEvent(String sessionId, String turnId) {
        super(sessionId);
        if (turnId == null || turnId.trim().isEmpty()) {
            throw new JellyfishException("turn id must not be blank");
        }
        this.turnId = turnId;
    }

    /**
     * 获取被取消的回合标识。
     * <p>
     * 与外壳可靠 lane 上那条回合事件里的 {@code turnId} 是同一个值，需要把「这次取消」与那一轮的
     * 全部输出关联起来时用它。
     *
     * @return 回合标识，保证非空白
     */
    public String getTurnId() {
        return turnId;
    }

    @Override
    public String toString() {
        return "TurnCancelledEvent{session=" + getSessionId() + ", turn=" + turnId + '}';
    }
}
