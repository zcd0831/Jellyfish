package zcd.jellyfish.server;

import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「客户端连上不读」时，那次阻塞写会不会被掐断——这条判据的答案是 N-40 的唯一出路。
 * <p>
 * <b>为什么必须真起 Undertow + 真 socket</b>：要验的正是「写不进 socket 时谁会来解开它」，
 * 把 exchange 换成 mock 就永远写不进去、也就永远不会卡，用例会恒绿。因此本用例会真的占用
 * 一个回环端口（绑 0 端口，用完即关），并且只在「服务端自己把这次写判死」时才是绿的。
 * <p>
 * <b>这条也是那次实验的关键修正</b>：先前用 {@code setServerOption(Options.WRITE_TIMEOUT, …)}
 * 试过，写线程不回来——因为 {@code Options.WRITE_TIMEOUT} 是 <b>XNIO 的通道选项</b>，
 * 而 {@code setServerOption} 把它放进了 UndertowOptions 的那张表里；{@code HttpOpenListener} 读的
 * 是 {@code channel.getOption(Options.WRITE_TIMEOUT)}（即连接自己的选项表），因此要用
 * {@code setSocketOption} 才会落到那里并装上 {@code WriteTimeoutStreamSinkConduit}。
 * <b>本用例直接用生产那一段配置</b>（{@link JellyfishServer#applyConnectionLimits}），
 * 因此把选项放错表、或把它从构建器上摘掉，这里就会红。
 *
 * @author zcd
 */
@DisplayName("SSE 卡住的写")
class StalledSseClientTest {

    /** 写超时 1 秒：如果它管用，几秒内就该有结论。 */
    private static final int WRITE_TIMEOUT_SECONDS = 1;

    /** 等服务端自己判死这次写的上限；给足余量，避免 CI 上偶发假红。 */
    private static final long WRITE_FAILED_WAIT_SECONDS = 15L;

    /** 每次写的数据量：足够把 socket 缓冲区填满（缺省收发缓冲远小于它）。 */
    private static final int CHUNK_BYTES = 64 * 1024;

    @Test
    @Timeout(60)
    @DisplayName("写超时到点就掐断那条连接，写线程随之收场（许可才可能被归还）")
    void blockedWrite_should_be_released_by_writeTimeout() throws Exception {
        CountDownLatch writeFailed = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        int port = freePort();
        // 与生产完全同一段配置：把选项放错表、或忘了调它，本用例就会红
        Undertow server = JellyfishServer.applyConnectionLimits(
                        Undertow.builder().addHttpListener(port, "127.0.0.1"),
                        ServerConfig.builder("127.0.0.1", port).writeTimeoutSeconds(WRITE_TIMEOUT_SECONDS).build())
                .setHandler(exchange -> streamForever(exchange, writeFailed, failure))
                .build();
        server.start();
        Socket client = new Socket();
        try {
            client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            client.setReceiveBufferSize(1024);
            OutputStream toServer = client.getOutputStream();
            toServer.write(("GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            toServer.flush();

            // 客户端此后一个字都不读，也不关掉：这才是 N-40 的场景（连接还活着，只是不读）
            assertTrue(writeFailed.await(WRITE_FAILED_WAIT_SECONDS, TimeUnit.SECONDS),
                    "写线程没有被写超时解开——客户端不读时它会永久占住一条流与它的许可");
            assertNotNull(failure.get(), "写线程应当以异常收场（上层据此走断连收尾）");
        } finally {
            client.close();
            stopQuietly(server);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("静默短于时限的流照常活着（这正是 keepalive 15 秒 < 时限 60 秒那条余量）")
    void idleStream_should_survive_when_idle_shorter_than_writeTimeout() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        int port = freePort();
        // 时限 2 秒、静默 1 秒：活着的流每 15 秒有一帧 keepalive，落在这个窗口内
        final int idleMillis = 1000;
        Undertow server = JellyfishServer.applyConnectionLimits(
                        Undertow.builder().addHttpListener(port, "127.0.0.1"),
                        ServerConfig.builder("127.0.0.1", port).writeTimeoutSeconds(2).build())
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream");
                    exchange.dispatch(() -> {
                        try {
                            OutputStream out = exchange.getOutputStream();
                            out.write(": first\n\n".getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            // 静默一段（SSE 的常态正是「写完一帧之后等下一个事件」）
                            Thread.sleep(idleMillis);
                            out.write(": second\n\n".getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                        } catch (IOException e) {
                            failure.compareAndSet(null, "写失败: " + e);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.endExchange();
                            finished.countDown();
                        }
                    });
                    exchange.startBlocking();
                })
                .build();
        server.start();
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(10000);
            client.getOutputStream().write(
                    ("GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            String received = readUntilEof(client);
            assertTrue(received.contains("first"), received);
            assertTrue(received.contains("second"), received);
            assertTrue(finished.await(5, TimeUnit.SECONDS), "服务端的写没有正常结束");
            assertNull(failure.get(), String.valueOf(failure.get()));
        } finally {
            stopQuietly(server);
        }
    }

    /**
     * 读到 EOF 为止（服务端写完就结束响应）。
     *
     * @param client 客户端连接
     * @return 收到的原文
     * @throws IOException 读取失败时抛出
     */
    private static String readUntilEof(Socket client) throws IOException {
        StringBuilder text = new StringBuilder();
        byte[] buffer = new byte[1024];
        while (true) {
            int read = client.getInputStream().read(buffer);
            if (read < 0) {
                return text.toString();
            }
            text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
    }

    /**
     * 一直往这条连接上写，直到写抛错为止。
     * <p>
     * 走 blocking 出口（{@code dispatch} + {@code startBlocking} + {@code getOutputStream()}），
     * 与 SSE 的写路径同型：正是「单写者 + 阻塞写」让客户端不读时写线程停在这里。
     *
     * @param exchange    HTTP 交换对象
     * @param writeFailed 写失败时倒数的闩
     * @param failure     记下写失败的原因
     */
    private static void streamForever(HttpServerExchange exchange, CountDownLatch writeFailed,
                                      AtomicReference<IOException> failure) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream");
        exchange.dispatch(() -> {
            byte[] chunk = new byte[CHUNK_BYTES];
            Arrays.fill(chunk, (byte) 'x');
            try {
                OutputStream out = exchange.getOutputStream();
                while (true) {
                    out.write(chunk);
                    out.flush();
                }
            } catch (IOException e) {
                failure.compareAndSet(null, e);
                writeFailed.countDown();
            } finally {
                exchange.endExchange();
            }
        });
        exchange.startBlocking();
    }

    /**
     * 停服务但不等它——写线程若还卡着，{@code stop()} 会跟着卡，那会把整个用例拖到超时。
     * <p>
     * 判据本身已经由上面的断言给出，这里只负责不让收尾掩盖掉它。
     *
     * @param server 待停止的服务
     * @throws InterruptedException 等待被中断时抛出
     */
    private static void stopQuietly(Undertow server) throws InterruptedException {
        Thread stopper = new Thread(server::stop, "test-undertow-stop");
        stopper.setDaemon(true);
        stopper.start();
        stopper.join(5000L);
    }

    /**
     * 起一个回环端口，交给 Undertow 自己绑。
     * <p>
     * 先绑 0 再立刻关掉拿端口号：Undertow 的 {@code addHttpListener} 只接受具体端口。
     * 这个窗口里的抢占风险在回环上可以忽略。
     *
     * @return 可用端口号
     * @throws IOException 拿不到端口时抛出
     */
    private static int freePort() throws IOException {
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
