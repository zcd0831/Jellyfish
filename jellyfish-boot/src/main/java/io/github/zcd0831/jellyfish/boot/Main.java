package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.di.AppComponent;
import io.github.zcd0831.jellyfish.di.DaggerAppComponent;
import io.github.zcd0831.jellyfish.di.InfrastructureModule;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.shell.tui.TuiShell;

/**
 * 只做三件事：解析配置 → 创建组件 → 启动外壳（技术决策 D04-4）。
 * Ctrl-C 的处理：关闭钩子取消在途模型请求，并等主线程把取消结果提交完（M14、D05-4）。
 */
public final class Main {

    private static final long SHUTDOWN_GRACE_MILLIS = 2000L;

    public static void main(String[] args) {
        BootOptions options = BootOptions.parse(args);
        DataDirectory dir = DataDirectory.of(options.dataDir());

        final AppComponent component = DaggerAppComponent.builder()
                .infrastructureModule(new InfrastructureModule(dir, System.out))
                .build();
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                component.httpJsonClient().cancelActive();
                try {
                    mainThread.join(SHUTDOWN_GRACE_MILLIS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "jellyfish-shutdown"));

        component.instanceLock().acquire();
        try {
            component.schemaMigrator().migrate();
            // 启动打印生效值，一条日志解决"我改的怎么没生效"（D12-3）
            System.out.println("data-dir=" + dir.root()
                    + "  log-level=" + options.logLevel()
                    + "  maxReActRounds=" + component.policyRepository().load().maxReActRounds()
                    + "  schemaVersion=1");
            new TuiShell(component.startSessionUseCase(), component.runSessionUseCase(), System.in, System.out)
                    .run();
        } finally {
            component.instanceLock().release();
        }
    }
}
