package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.di.AppComponent;
import io.github.zcd0831.jellyfish.di.DaggerAppComponent;
import io.github.zcd0831.jellyfish.di.InfrastructureModule;
import io.github.zcd0831.jellyfish.di.ShellModule;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理池必须是守护线程：否则用户 exit 后 main 已返回、JVM 仍在等子代理线程，
 * 关闭钩子不执行、终端回不来（08-application §8.9.4.1）。
 */
class SubagentPoolTest {

    @TempDir
    Path tmp;

    @Test
    void 子代理池的线程是守护线程() throws Exception {
        AppComponent component = DaggerAppComponent.builder()
                .infrastructureModule(new InfrastructureModule(DataDirectory.of(tmp.toString())))
                .shellModule(new ShellModule(new PrintStream(new ByteArrayOutputStream())))
                .build();

        ExecutorService pool = component.subagentPool();
        AtomicReference<Thread> worker = new AtomicReference<Thread>();
        pool.submit(new Runnable() {
            @Override
            public void run() {
                worker.set(Thread.currentThread());
            }
        }).get(5, TimeUnit.SECONDS);

        assertTrue(worker.get().isDaemon(), "子代理线程不是守护线程，exit 会挂住");
        assertEquals("jellyfish-subagent", worker.get().getName(), "线程要有名字，便于排查");
    }
}
