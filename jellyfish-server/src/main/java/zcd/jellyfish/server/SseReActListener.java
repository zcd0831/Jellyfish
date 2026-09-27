package zcd.jellyfish.server;

import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.server.dto.TurnCancelledEvent;
import zcd.jellyfish.server.dto.TurnCompleteEvent;
import zcd.jellyfish.server.dto.TurnErrorEvent;
import zcd.jellyfish.server.dto.TurnTextEvent;
import zcd.jellyfish.server.dto.TurnThinkingEvent;
import zcd.jellyfish.server.dto.TurnToolDoneEvent;
import zcd.jellyfish.server.dto.TurnToolStartEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 * <b>turnId 由外壳自己生成，不用 {@code ReActTurn.getTurnId()}</b>：后者只有在 {@code harness.chat}
 * <b>返回之后</b>才拿得到，而监听器必须在 {@code chat} 之前交出去——若等到那时再回填，任何在
 * 「提交任务」与「拿到句柄」之间产生的回调都会带上 {@code null}。turnId 对客户端只是一个关联标识，
 * 由外壳生成既消除竞态，也让同一条流的所有事件天然共享同一个 id。
 * <p>
 * 线程安全：队列自带并发语义；两个标识都是 {@code final}。
 *
 * @author zcd
 */
public final class SseReActListener implements ReActListener {

    /** 事件队列。 */
    private final BlockingQueue<SseEvent> queue = new LinkedBlockingQueue<SseEvent>();

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
        return queue.poll(Math.max(1, seconds), TimeUnit.SECONDS);
    }

    /**
     * 立即取一条事件（不等待），供测试使用。
     *
     * @return 事件；队列为空时返回 {@code null}
     */
    SseEvent pollNow() {
        return queue.poll();
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
    public void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output) {
        queue.offer(new SseEvent("tool_done",
                new TurnToolDoneEvent(turnId, toolCallId, toolName, success, output), false));
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

    @Override
    public void onError(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "回合失败" : error.getMessage();
        queue.offer(new SseEvent("error", new TurnErrorEvent(turnId, "INTERNAL_ERROR", message), true));
    }
}
