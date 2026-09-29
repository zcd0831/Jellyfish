package zcd.jellyfish.plugin.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 内存传输：让连接对象的协议逻辑可以在不起真进程的前提下被完整驱动。
 * <p>
 * <b>为什么需要它</b>：连接的绝大部分代码是「按 id 配对、超时摘等待位、分发通知、回绝未知能力」，
 * 而这些都是协议逻辑，与「消息其实来自子进程管道」无关。用真进程测它们，就得凑齐握手超时、
 * 乱序应答、server 主动请求这些很难自然出现的时序。
 * <p>
 * <b>应答是同步生成的</b>：{@link #send(String)} 里就把 {@code responder} 的答复放进队列，
 * 而读线程随后取走——这与「对面立刻回」是一致的，也正是测试想要的确定性。
 * <p>
 * 测试用。
 *
 * @author zcd
 */
final class FakeTransport implements McpTransport {

    /** 待读的消息。 */
    private final BlockingQueue<String> inbound = new LinkedBlockingQueue<String>();

    /** 已发出的消息原文。 */
    private final List<String> sent = Collections.synchronizedList(new ArrayList<String>());

    /** 应答生成器：返回 {@code null} 表示「这条不回」（用来构造超时）。 */
    private volatile Function<ObjectNode, String> responder = message -> null;

    /** 是否已关闭。 */
    private volatile boolean closed;

    /** 关闭信号，用于叫醒阻塞中的读。 */
    private final CountDownLatch closedLatch = new CountDownLatch(1);

    /**
     * 设置应答生成器。
     *
     * @param responder 应答生成器，不可为 {@code null}
     */
    void responder(Function<ObjectNode, String> responder) {
        this.responder = responder;
    }

    /**
     * 主动推一条消息（通知或 server 发起的请求）。
     *
     * @param line 消息文本
     */
    void push(String line) {
        inbound.add(line);
    }

    /**
     * 取已发出的消息原文。
     *
     * @return 不可变列表
     */
    List<String> sentRaw() {
        synchronized (sent) {
            return new ArrayList<String>(sent);
        }
    }

    /**
     * 取已发出的最后一条匹配方法名的消息。
     *
     * @param method 方法名
     * @return 消息节点；没找到时返回 {@code null}
     */
    ObjectNode lastRequest(String method) {
        ObjectNode found = null;
        for (String line : sentRaw()) {
            ObjectNode message = McpJson.parseObject(line);
            if (method.equals(McpJson.text(message, "method", null))) {
                found = message;
            }
        }
        return found;
    }

    /**
     * 取已发出的全部匹配方法名的消息。
     *
     * @param method 方法名
     * @return 消息列表
     */
    List<ObjectNode> requests(String method) {
        List<ObjectNode> found = new ArrayList<ObjectNode>();
        for (String line : sentRaw()) {
            ObjectNode message = McpJson.parseObject(line);
            if (method.equals(McpJson.text(message, "method", null))) {
                found.add(message);
            }
        }
        return found;
    }

    @Override
    public void send(String json) throws IOException {
        if (closed) {
            throw new IOException("transport closed");
        }
        sent.add(json);
        ObjectNode message = McpJson.parseObject(json);
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) {
            return;
        }
        String reply = responder.apply(message);
        if (reply != null) {
            inbound.add(reply);
        }
    }

    @Override
    public String readLine() throws IOException {
        while (!closed) {
            try {
                String line = inbound.poll(30L, TimeUnit.MILLISECONDS);
                if (line != null) {
                    return line;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean isAlive() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
        closedLatch.countDown();
    }

    /**
     * 组装一条成功应答。
     *
     * @param id     请求 id
     * @param result 结果 JSON
     * @return 应答文本
     */
    static String result(long id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    }

    /**
     * 组装一条错误应答。
     *
     * @param id      请求 id
     * @param code    错误码
     * @param message 错误信息
     * @return 应答文本
     */
    static String error(long id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}";
    }
}
