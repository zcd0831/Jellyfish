package zcd.jellyfish.infra.event;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件通道线程工厂：命名 + 守护线程。
 * <p>
 * 守护线程保证线程池不会阻止 JVM 退出；统一前缀便于在拒绝策略中识别「当前是否枢纽线程」。
 *
 * @author zcd
 */
final class EventThreadFactory implements ThreadFactory {

    /** 通知线程名前缀。 */
    static final String NAME_PREFIX = "jellyfish-event-";

    /** 线程序号。 */
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, NAME_PREFIX + counter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
