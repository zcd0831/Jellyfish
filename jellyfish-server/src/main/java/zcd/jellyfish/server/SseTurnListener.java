package zcd.jellyfish.server;

import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.ShellTurnListener;
import zcd.jellyfish.server.dto.TurnBlockedEvent;
import zcd.jellyfish.server.dto.TurnCancelledEvent;
import zcd.jellyfish.server.dto.TurnCompleteEvent;
import zcd.jellyfish.server.dto.TurnErrorEvent;
import zcd.jellyfish.server.dto.TurnStartEvent;
import zcd.jellyfish.server.dto.TurnTextEvent;
import zcd.jellyfish.server.dto.TurnThinkingEvent;
import zcd.jellyfish.server.dto.TurnToolDoneEvent;
import zcd.jellyfish.server.dto.TurnToolOutputEvent;
import zcd.jellyfish.server.dto.TurnToolStartEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把可靠 lane 上的回合事件翻译成 SSE 事件并投进队列。
 * <p>
 * <b>为什么用队列而不是直接写 socket</b>：事件在 {@code react} / 工具输出泵线程上到达，
 * 而写 socket 会阻塞——直接写等于把「客户端慢」的代价转嫁到模型循环上（一次阻塞写就拖住整轮推理）。
 * 队列把两者的线程解耦：这里只做 {@code offer}（不阻塞），写由 {@link zcd.jellyfish.server.handler.ChatHandler}
 * 在自己的工作线程上做。
 * <p>
 * <b>队列为什么无界</b>：一轮的文本增量总量受模型输出上限约束，而消费者（写线程）始终在跑；
 * 有界队列在满时要么阻塞发布线程（拖死回合）、要么丢事件（丢正文即错），两者都比「多占几 KB 内存」更糟。
 * <p>
 * <b>实时工具输出是这条规则唯一的例外，因此它自带计数上限</b>：它的生产者是子进程而不是模型
 * （{@code yes} 能一直吐），而消费者可能是慢客户端，无界队列会随一个卡住的连接一起涨。
 * 它可丢——权威文本随后由 {@code tool_done} 给出——所以超额时丢弃并计数，而丢弃<b>不阻塞反应线程</b>。
 * <p>
 * <b>turnId 不再由本类拼</b>：内核在回合启动之前就确定了它，每条事件自带（见 {@link ShellTurnEvent}），
 * 因此不会出现「早期回调带 {@code null}」的竞态——那是改造前外壳自己造 turnId 的原因。
 * <p>
 * 线程安全：队列自带并发语义；待发条数用原子计数。
 *
 * @author zcd
 */
public final class SseTurnListener implements ShellTurnListener {

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

    /** 会话标识：终态事件里要带它。 */
    private final String sessionId;

    /**
     * 构造监听器。
     *
     * @param sessionId 会话标识，不可为空白
     */
    public SseTurnListener(String sessionId) {
        this.sessionId = sessionId;
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
    public void onTurnEvent(ShellTurnEvent event) {
        String turnId = event.getTurnId();
        switch (event.getKind()) {
            case STARTED:
                queue.offer(new SseEvent("turn_start", new TurnStartEvent(turnId, sessionId), false));
                break;
            case TEXT:
                queue.offer(new SseEvent("text", new TurnTextEvent(turnId, event.getText()), false));
                break;
            case THINKING:
                queue.offer(new SseEvent("thinking", new TurnThinkingEvent(turnId, event.getText()), false));
                break;
            case TOOL_STARTED:
                queue.offer(new SseEvent("tool_start", new TurnToolStartEvent(turnId, event.getToolCallId(),
                        event.getToolName()), false));
                break;
            case TOOL_OUTPUT:
                onToolOutput(turnId, event);
                break;
            case TOOL_COMPLETED:
                queue.offer(new SseEvent("tool_done", new TurnToolDoneEvent(turnId, event.getToolCallId(),
                        event.getToolName(), event.isSuccess(), event.getOutput(), event.getMetadata()), false));
                break;
            case COMPLETED:
                queue.offer(new SseEvent("done", new TurnCompleteEvent(turnId, sessionId, event.getText(),
                        event.getRounds(), event.isTruncated()), true));
                break;
            case CANCELLED:
                queue.offer(new SseEvent("cancelled", new TurnCancelledEvent(turnId, sessionId), true));
                break;
            case BLOCKED:
                // 写成独立终态而不是 error：客户端对两者的处理不同（改请求 vs 报障），
                // 且这不是错误——没抛异常、没资源故障、客户端也没断开
                queue.offer(new SseEvent("turn_blocked", new TurnBlockedEvent(turnId, sessionId,
                        event.getReason()), true));
                break;
            case ERROR:
                String message = event.getError() == null || event.getError().getMessage() == null
                        ? "回合失败" : event.getError().getMessage();
                queue.offer(new SseEvent("error", new TurnErrorEvent(turnId, "INTERNAL_ERROR", message), true));
                break;
            default:
                break;
        }
    }

    /**
     * 投递一条实时工具输出，超出待发上限即丢弃并计数。
     *
     * @param turnId 回合标识
     * @param event  事件
     */
    private void onToolOutput(String turnId, ShellTurnEvent event) {
        String chunk = event.getText();
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        // 先占位再判上限：占位与入队之间不能有别的路径减回去，否则上限就成了摆设
        if (pendingToolOutput.incrementAndGet() > MAX_PENDING_TOOL_OUTPUT) {
            pendingToolOutput.decrementAndGet();
            droppedToolOutput.incrementAndGet();
            return;
        }
        queue.offer(new SseEvent(TOOL_OUTPUT, new TurnToolOutputEvent(turnId, event.getToolCallId(),
                event.getToolName(), chunk), false));
    }
}
