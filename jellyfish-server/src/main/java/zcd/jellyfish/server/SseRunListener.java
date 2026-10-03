package zcd.jellyfish.server;

import zcd.jellyfish.core.runtime.AgentRunEvent;
import zcd.jellyfish.core.runtime.AgentRunSnapshot;
import zcd.jellyfish.server.dto.RunFinishedEvent;
import zcd.jellyfish.server.dto.RunStartedEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * 把 agent run 的生命周期事件翻译成 SSE 事件并投进队列。
 * <p>
 * <b>与 {@link SseTurnListener} / {@link SseContributionListener} 的关系</b>：同一个角色、第三来源。
 * 回合事件走可靠 lane，插件贡献走尽力 lane，而 run 事件来自运行时的
 * {@link zcd.jellyfish.core.runtime.RunEventBus}。三者都只往队列里投，写出仍只有
 * {@link zcd.jellyfish.server.handler.ChatHandler} 的写循环一个写者——多一个来源不改变这条纪律。
 * <p>
 * <b>为什么按父会话过滤</b>：总线是进程级的，而每条 SSE 流只该看到自己这条会话里的子代理。
 * 不过滤的话，A 会话的客户端会看到 B 会话的子代理在跑。
 * <p>
 * <b>为什么只映射 STARTED / FINISHED</b>：{@code STEP}（每轮推进）与输出流是另一档
 * （见 {@code design/subagent-runtime-p1.md} D-P1-5），前者要内核 {@code loop} 的进度钩子，
 * 后者要可丢通道，都还没接。总线是同步扇出的，订阅者挡在 run 推进之间，因此本类只做
 * {@code offer} 这种常量级动作，绝不做 I/O。
 * <p>
 * <b>队列为什么无界</b>：一个 run 只发两条事件，一条流的待发量受「同一会话同时在跑的 run 数」
 * 约束（缺省并发 3），因此它不可能像工具输出那样被一个吐个不停的子进程喂大。
 * <p>
 * 线程安全：队列自带并发语义。
 *
 * @author zcd
 */
public final class SseRunListener implements Consumer<AgentRunEvent> {

    /** SSE 事件名：run 开始。 */
    private static final String RUN_STARTED = "run_started";

    /** SSE 事件名：run 终结。 */
    private static final String RUN_FINISHED = "run_finished";

    /** 待写出的 SSE 事件队列。 */
    private final BlockingQueue<SseEvent> queue = new LinkedBlockingQueue<SseEvent>();

    /** 本流对应的会话标识：只有以此会话为父的 run 才发往本流。 */
    private final String sessionId;

    /**
     * 构造监听器。
     *
     * @param sessionId 会话标识，不可为空白
     */
    public SseRunListener(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 立即取一条事件（不等待），供写循环与测试使用。
     *
     * @return 事件；队列为空时返回 {@code null}
     */
    public SseEvent pollNow() {
        return queue.poll();
    }

    @Override
    public void accept(AgentRunEvent event) {
        AgentRunSnapshot run = event.getRun();
        if (!sessionId.equals(run.getParentSessionId())) {
            return;
        }
        switch (event.getKind()) {
            case STARTED:
                queue.offer(new SseEvent(RUN_STARTED, new RunStartedEvent(run.getRunId(), run.getParentRunId(),
                        run.getRootRunId(), run.getParentSessionId(), run.getAgentId(), run.getSessionId(),
                        run.getToolCallId(), run.getStartedAt())));
                break;
            case FINISHED:
                queue.offer(new SseEvent(RUN_FINISHED, new RunFinishedEvent(run.getRunId(), run.getParentRunId(),
                        run.getRootRunId(), run.getParentSessionId(), run.getAgentId(), run.getSessionId(),
                        run.getStatus().name(), run.getRounds(), run.getTotalTokens(), run.getStartedAt(),
                        run.getFinishedAt())));
                break;
            default:
                // STEP / 输出流：待内核 loop 进度钩子与可丢通道，见类注释
                break;
        }
    }
}
