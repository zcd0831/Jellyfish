package zcd.jellyfish.infra.event;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交互枢纽线程工厂：命名 + 守护线程。
 * <p>
 * 守护线程保证线程池不会阻止 JVM 退出；统一前缀便于在拒绝策略中识别「当前是否枢纽线程」。
 *
 * @author zcd
 */
final class EventThreadFactory implements ThreadFactory {

    /** 通知线程名前缀。 */
    static final String NAME_PREFIX = "jellyfish-event-";

    /** ISOLATED 回调线程名前缀。 */
    static final String CALLBACK_PREFIX = "jellyfish-callback-";

    /** 线程名前缀。 */
    private final String namePrefix;

    /** 线程序号。 */
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * 创建通知线程工厂。
     */
    EventThreadFactory() {
        this(NAME_PREFIX);
    }

    /**
     * 创建指定前缀的线程工厂。
     *
     * @param namePrefix 线程名前缀，不可为空
     */
    EventThreadFactory(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, namePrefix + counter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
