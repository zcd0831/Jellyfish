package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.di.AppComponent;
import io.github.zcd0831.jellyfish.di.DaggerAppComponent;
import io.github.zcd0831.jellyfish.di.InfrastructureModule;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;

/**
 * 只做三件事：解析配置 → 创建组件 → 启动外壳（技术决策 D04-4）。
 * Ctrl-C 的处理：关闭钩子取消在途模型请求，并等主线程把取消结果提交完（M14、D05-4）。
 */
public final class Main {

    private static final long SHUTDOWN_GRACE_MILLIS = 2000L;

    /** 控制台宽度：优先 COLUMNS，其次退化为 100（渲染器依赖它做折行）。 */
    private static int terminalWidth() {
        String columns = System.getenv("COLUMNS");
        try {
            return columns == null ? 100 : Integer.parseInt(columns);
        } catch (NumberFormatException ignored) {
            return 100;
        }
    }

    /** 凭据只从环境变量读；缺失的供应商提示一条，不阻塞启动（D09-4）。 */
    private static void warnMissingCredentials(AppComponent component) {
        for (io.github.zcd0831.jellyfish.domain.action.model.Provider provider
                : component.catalogRepository().providers()) {
            String env = provider.credential() == null ? null : provider.credential().getApiKeyRef();
            if (env != null && !env.isEmpty() && System.getenv(env) == null) {
                System.out.println("provider " + provider.id().getValue() + ": 环境变量 " + env + " 未设置");
            }
        }
    }

    public static void main(String[] args) {
        BootOptions options = BootOptions.parse(args);
        DataDirectory dir = DataDirectory.of(options.dataDir());

        // 日志目录与级别必须先于任何 logger 初始化（D18-4）
        System.setProperty("jellyfish.log.dir", dir.logs().toString());
        System.setProperty("jellyfish.log.level", options.logLevel());
        io.github.zcd0831.jellyfish.application.shared.TechnicalLog.use(
                new io.github.zcd0831.jellyfish.infrastructure.log.Log4jTechnicalLog());

        final AppComponent component = DaggerAppComponent.builder()
                .infrastructureModule(new InfrastructureModule(dir))
                .shellModule(new io.github.zcd0831.jellyfish.di.ShellModule(System.out, terminalWidth()))
                .build();
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                component.subagentPool().shutdownNow();
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
            warnMissingCredentials(component);
            component.tuiApp().run();
        } finally {
            component.instanceLock().release();
        }
    }
}
