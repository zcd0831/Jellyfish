package zcd.jellyfish.infra.llm;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 在调用线程上直接执行任务的 {@link ExecutorService}，仅供测试。
 * <p>
 * <b>为什么需要一个</b>：插件适配器把阻塞式传输丢给流式线程池，而真实线程池会让断言面对
 * 「事件什么时候到」这个不确定量。用直接执行，测试里的因果顺序就与调用顺序一致——
 * 失败时看到的是真实的顺序问题，而不是线程调度。{@link ExecutorService} 有多个抽象方法，
 * 因此这里不能写成 lambda，只能落到一个具名类。
 *
 * @author zcd
 */
class DirectExecutorService extends AbstractExecutorService {

    /** 是否已关闭。 */
    private boolean shutdown;

    @Override
    public void execute(Runnable command) {
        if (shutdown) {
            throw new java.util.concurrent.RejectedExecutionException("executor is shut down");
        }
        command.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown;
    }
}
