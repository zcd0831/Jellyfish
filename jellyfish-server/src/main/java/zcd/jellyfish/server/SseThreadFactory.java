package zcd.jellyfish.server;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 写循环的线程工厂：命名 + 守护线程。
 * <p>
 * <b>为什么需要一组专用线程</b>：写 socket 会阻塞，而阻塞写没有可用的超时——客户端连上之后不读，
 * 内核的发送缓冲一满，{@code write} 就一直等。这样的线程绝不能是 Undertow 的工作线程：
 * 工作线程还要服务 {@code /health}、{@code /commands} 这些短请求，被几条卡住的流占满之后，
 * 「短请求仍有人应答」这句承诺就不成立了（原本 {@code maxStreams=16} 而工作线程下限是 8，
 * 八条流就足以占满）。换到本工厂给出的线程上之后，卡住的代价被限制在「一条流一个线程」这个量级。
 * <p>
 * <b>守护线程</b>：它们可能长时间阻塞在写操作上，不该阻止 JVM 退出——这也与
 * {@code JellyfishServer.stop()} 的处理一致（关闭时只 {@code shutdownNow}，不等它们收敛）。
 *
 * @author zcd
 */
final class SseThreadFactory implements ThreadFactory {

    /** 线程名前缀。 */
    private static final String NAME_PREFIX = "jellyfish-sse-";

    /** 线程序号。 */
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, NAME_PREFIX + counter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
