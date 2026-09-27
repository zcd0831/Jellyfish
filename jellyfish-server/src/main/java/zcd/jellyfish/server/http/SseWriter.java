package zcd.jellyfish.server.http;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SSE（Server-Sent Events）写出器：把事件按 {@code event:}/{@code data:} 分帧并立即 flush。
 * <p>
 * <b>为什么每次写完都 flush</b>：SSE 的全部价值在于「现在就到达」，缓冲会把流式变成「读完才显示」。
 * 每一次 flush 都对应一个可被客户端立刻消费的事件。
 * <p>
 * <b>为什么 {@code data} 用 JSON 单行表示</b>：SSE 的 {@code data} 字段以换行分隔，裸换行会把一条事件
 * 拆成两条 {@code data} 行。JSON 序列化天然把换行转义成 {@code \n}，因此「一律先序列化成 JSON」既解决
 * 了这个坑，也让前端只认一种载荷格式。
 * <p>
 * <b>写失败就是断连信号</b>：客户端关闭连接后 {@code write} 抛 {@link IOException}，
 * 上层据此取消回合——这正是「单写者 + 阻塞写」模型下最自然的断连探测，不需要心跳超时之外的另一套机制。
 * <p>
 * 非线程安全：全部方法都必须由同一个线程调用（本方案里是 Undertow 的工作线程）。
 *
 * @author zcd
 */
public final class SseWriter {

    /** SSE 内容类型。 */
    private static final String CONTENT_TYPE = "text/event-stream; charset=utf-8";

    /** 让反向代理不要缓冲事件流的响应头名。 */
    private static final HttpString HEADER_ACCEL_BUFFERING = new HttpString("X-Accel-Buffering");

    /** 输出流。 */
    private final OutputStream out;

    /**
     * 构造 SSE 写出器。
     *
     * @param out 响应输出流，不可为 {@code null}
     */
    public SseWriter(OutputStream out) {
        this.out = out;
    }

    /**
     * 准备一次 SSE 响应：写响应头并返回写出器。
     * <p>
     * {@code Cache-Control: no-cache} 与 {@code X-Accel-Buffering: no} 是给中间层看的：
     * 前者禁止代理缓存事件流，后者让 Nginx 之类的反向代理不要缓冲。
     *
     * @param exchange HTTP 交换对象，不可为 {@code null}
     * @return SSE 写出器
     */
    public static SseWriter prepare(HttpServerExchange exchange) {
        exchange.setStatusCode(Responses.OK);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE);
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
        exchange.getResponseHeaders().put(Headers.CONNECTION, "keep-alive");
        exchange.getResponseHeaders().put(HEADER_ACCEL_BUFFERING, "no");
        return new SseWriter(exchange.getOutputStream());
    }

    /**
     * 写一条事件。
     *
     * @param event 事件名
     * @param data  载荷对象，会被序列化为单行 JSON，可为 {@code null}
     * @throws IOException 客户端已断开时抛出
     */
    public void event(String event, Object data) throws IOException {
        writeFrame("event: " + event + "\ndata: " + ObjectMapperWrapper.writeValueAsString(data) + "\n\n");
    }

    /**
     * 写一条注释帧作为 keepalive。
     * <p>
     * 注释帧（以 {@code :} 开头）会被 SSE 客户端忽略，只用来让连接在空闲期保持活跃。
     *
     * @param text 注释文本
     * @throws IOException 客户端已断开时抛出
     */
    public void comment(String text) throws IOException {
        writeFrame(": " + text + "\n\n");
    }

    /**
     * 写出一个帧并立即 flush。
     *
     * @param frame 帧文本
     * @throws IOException 客户端已断开时抛出
     */
    private void writeFrame(String frame) throws IOException {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
