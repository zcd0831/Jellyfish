package zcd.jellyfish.server;

import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.server.dto.TurnBlockedEvent;
import zcd.jellyfish.server.dto.TurnCancelledEvent;
import zcd.jellyfish.server.dto.TurnCompleteEvent;
import zcd.jellyfish.server.dto.TurnErrorEvent;
import zcd.jellyfish.server.dto.TurnTextEvent;
import zcd.jellyfish.server.dto.TurnThinkingEvent;
import zcd.jellyfish.server.dto.TurnToolDoneEvent;
import zcd.jellyfish.server.dto.TurnToolStartEvent;
import zcd.jellyfish.server.dto.TurnToolOutputEvent;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把 {@code ReActListener} 的回调翻译成 SSE 事件并投进队列。
 * <p>
 * <b>为什么用队列而不是直接写 socket</b>：回调发生在 {@code react} 线程上，而写 socket 会阻塞——
 * 直接写等于把「客户端慢」的代价转嫁到模型循环上（一次阻塞写就拖住整轮推理）。
 * 队列把两者的线程解耦：这里只做 {@code offer}（不阻塞），写由 {@link ChatHandler} 在自己的线程上做。
 * <p>
 * <b>队列为什么无界</b>：一轮的文本增量总量受模型输出上限约束，而消费者（写线程）始终在跑；
 * 有界队列在满时要么阻塞 react 线程（拖死回合）、要么丢事件（丢正文即错），两者都比「多占几 KB 内存」更糟。
 * <p>
 * <b>实时工具输出是这条规则唯一的例外，因此它自带计数上限</b>：它的生产者是子进程而不是模型
 * （{@code yes} 能一直吐），而消费者可能是慢客户端，无界队列会随一个卡住的连接一起涨。
 * 它可丢——权威文本随后由 {@code tool_done} 给出——所以超额时丢弃并计数，而丢弃<b>不阻塞反应线程</b>。
 * <p>
 * <b>turnId 由外壳自己生成，不用 {@code ReActTurn.getTurnId()}</b>：后者只有在 {@code harness.chat}
 * <b>返回之后</b>才拿得到，而监听器必须在 {@code chat} 之前交出去——若等到那时再回填，任何在
 * 「提交任务」与「拿到句柄」之间产生的回调都会带上 {@code null}。turnId 对客户端只是一个关联标识，
 * 由外壳生成既消除竞态，也让同一条流的所有事件天然共享同一个 id。
 * <p>
 * 线程安全：队列自带并发语义；两个标识都是 {@code final}；待发条数用原子计数。
 *
 * @author zcd
 */
public final class SseReActListener implements ReActListener {

    /** 实时输出的事件名；出队列时要按它分辨并回减待发计数，因此定义成常量而不是两处手写。 */
    private static final String TOOL_OUTPUT = "tool_output";

    /**
     * 待发送的实时输出片段上限。
     * <p>
     * 按「条数」而不是「字节数」定上限，是因为调度单位就是条：一条事件就是一帧，而泵每次交付
     * 的片段大小固定（几 KB 级），因此条数上限同时也就给了内存上限。取 64 是权衡：
     * 它足以让一次刷新把半秒内的输出成批送到，又不至于在客户端卡住时累积到肉眼可见的内存量。
     */
    private static final int MAX_PENDING_TOOL_OUTPUT = 64;

    /** 事件队列。 */
    private final BlockingQueue<SseEvent> queue = new LinkedBlockingQueue<SseEvent>();

    /** 待发送的实时输出条数：只统计未取走的那些。 */
    private final AtomicInteger pendingToolOutput = new AtomicInteger();

    /** 因超出上限而丢弃的片段数，供台账与测试观测。 */
    private final AtomicInteger droppedToolOutput = new AtomicInteger();

    /** 会话标识。 */
    private final String sessionId;

    /** 回合标识（外壳生成）。 */
    private final String turnId;

    /**
     * 构造监听器。
     *
     * @param sessionId 会话标识，不可为空白
     * @param turnId    回合标识（外壳生成），不可为空白
     */
    public SseReActListener(String sessionId, String turnId) {
        this.sessionId = sessionId;
        this.turnId = turnId;
    }

    /**
     * 取一条事件，最多等待指定秒数。
     *
     * @param seconds 等待秒数
     * @return 事件；超时返回 {@code null}
     * @throws InterruptedException 等待被中断时抛出
     */
    public SseEvent poll(int seconds) throws InterruptedException {
        SseEvent event = queue.poll(Math.max(1, seconds), TimeUnit.SECONDS);
        if (event != null && TOOL_OUTPUT.equals(event.getName())) {
            pendingToolOutput.decrementAndGet();
        }
        return event;
    }

    /**
     * 立即取一条事件（不等待），供测试使用。
     *
     * @return 事件；队列为空时返回 {@code null}
     */
    SseEvent pollNow() {
        SseEvent event = queue.poll();
        if (event != null && TOOL_OUTPUT.equals(event.getName())) {
            pendingToolOutput.decrementAndGet();
        }
        return event;
    }

    /**
     * 取因超出上限而丢弃的实时输出片段数。
     *
     * @return 丢弃条数
     */
    public int getDroppedToolOutput() {
        return droppedToolOutput.get();
    }

    @Override
    public void onText(String delta) {
        if (delta != null && !delta.isEmpty()) {
            queue.offer(new SseEvent("text", new TurnTextEvent(turnId, delta), false));
        }
    }

    @Override
    public void onThinking(String delta) {
        if (delta != null && !delta.isEmpty()) {
            queue.offer(new SseEvent("thinking", new TurnThinkingEvent(turnId, delta), false));
        }
    }

    @Override
    public void onToolCallStarted(String toolCallId, String toolName) {
        queue.offer(new SseEvent("tool_start", new TurnToolStartEvent(turnId, toolCallId, toolName), false));
    }

    @Override
    public void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output,
                                    Map<String, Object> metadata) {
        queue.offer(new SseEvent("tool_done",
                new TurnToolDoneEvent(turnId, toolCallId, toolName, success, output, metadata), false));
    }

    @Override
    public void onToolCallOutput(String toolCallId, String toolName, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        // 先占位再判上限：占位与入队之间不能有别的路径减回去，否则上限就成了摆设
        if (pendingToolOutput.incrementAndGet() > MAX_PENDING_TOOL_OUTPUT) {
            pendingToolOutput.decrementAndGet();
            droppedToolOutput.incrementAndGet();
            return;
        }
        queue.offer(new SseEvent(TOOL_OUTPUT, new TurnToolOutputEvent(turnId, toolCallId, toolName, chunk), false));
    }

    @Override
    public void onComplete(ReActResult result) {
        queue.offer(new SseEvent("done", new TurnCompleteEvent(turnId, sessionId,
                result.getContent(), result.getRounds(), result.isTruncated()), true));
    }

    @Override
    public void onCancelled() {
        queue.offer(new SseEvent("cancelled", new TurnCancelledEvent(turnId, sessionId), true));
    }

    /**
     * 回合在开始前被插件拦下。
     * <p>
     * 写成独立终态而不是 {@code error}：客户端对两者的处理不同（改请求 vs 报障），
     * 且这不是错误——没抛异常、没资源故障、客户端也没断开。
     *
     * @param reason 拦下的理由，可为 {@code null}
     */
    @Override
    public void onBlocked(String reason) {
        queue.offer(new SseEvent("turn_blocked", new TurnBlockedEvent(turnId, sessionId, reason), true));
    }

    @Override
    public void onError(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "回合失败" : error.getMessage();
        queue.offer(new SseEvent("error", new TurnErrorEvent(turnId, "INTERNAL_ERROR", message), true));
    }
}
