package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.di.AppComponent;
import io.github.zcd0831.jellyfish.di.DaggerAppComponent;
import io.github.zcd0831.jellyfish.di.InfrastructureModule;
import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;

/**
 * 只做三件事：解析配置 → 创建组件 → 启动外壳。
 * Ctrl-C 的处理：关闭钩子取消在途模型请求，并等主线程把取消结果提交完。
 */
public final class Main {

    private static final long SHUTDOWN_GRACE_MILLIS = 2000L;

    /** 凭据只从环境变量读；缺失的供应商提示一条，不阻塞启动。 */
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

        // 日志目录与级别必须先于任何 logger 初始化
        System.setProperty("jellyfish.log.dir", dir.logs().toString());
        System.setProperty("jellyfish.log.level", options.logLevel());
        io.github.zcd0831.jellyfish.application.shared.TechnicalLog.use(
                new io.github.zcd0831.jellyfish.infrastructure.log.Log4jTechnicalLog());

        final AppComponent component = DaggerAppComponent.builder()
                .infrastructureModule(new InfrastructureModule(dir))
                .shellModule(new io.github.zcd0831.jellyfish.di.ShellModule(System.out, options.noColor()))
                .build();
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                component.sessionCancellations().cancelAll();   // 尽力叫停所有在途调用
                component.subagentPool().shutdownNow();
                try {
                    mainThread.join(SHUTDOWN_GRACE_MILLIS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "jellyfish-shutdown"));

        int exitCode = withInstanceLock(component.instanceLock(), new Runnable() {
            @Override
            public void run() {
                startup(component, dir, options);
            }
        }, dir);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 单实例锁的获取与释放：跑完必须还锁，否则下次启动会被自己挡住。
     * 单独抽出来是为了让"异常退出也还锁"这条能被测到（{@code Main} 本身起真实终端，测不动）。
     */
    static int withInstanceLock(io.github.zcd0831.jellyfish.application.shared.port.InstanceLock lock,
                                Runnable body, DataDirectory dir) {
        lock.acquire();
        try {
            return guarded(body, dir.logs(), dir.root().toString());
        } finally {
            lock.release();
        }
    }

    /**
     * 最外层兜底站：领域不变量违反不在这里"修好"，只负责留证并给一句人话
     * （约定出处：Jellyfish/AGENTS.md「错误表达」）。{@code Error} 记完原样上抛。
     */
    static int guarded(Runnable body, java.nio.file.Path logsDir, String dataDir) {
        try {
            body.run();
            return 0;
        } catch (DomainInvariantViolation violated) {
            return report("domain_invariant", logsDir, violated, dataDir, "内部约定被违反");
        } catch (Error fatal) {
            report("fatal_error", logsDir, fatal, dataDir, "运行时致命错误");
            throw fatal;
        }
    }

    private static int report(String kind, java.nio.file.Path logsDir, Throwable error,
                              String dataDir, String what) {
        TechnicalLog.event("fatal." + kind, 0L,
                "type=" + error.getClass().getName(), "msg=" + error.getMessage());
        java.nio.file.Path written = CrashReport.write(logsDir, kind, error, dataDir);
        System.err.println(what + "：" + error.getMessage()
                + (written == null ? "" : "（现场快照：" + written + "）"));
        return 1;
    }

    private static void startup(AppComponent component, DataDirectory dir, BootOptions options) {
        // 启动技术清理必须先于任何读取：只处理文件层面残留（09-infrastructure §9.7）
        for (String warning : new io.github.zcd0831.jellyfish.infrastructure.file
                .FileSystemCleaner(dir).clean()) {
            System.out.println(warning);
        }
        component.schemaMigrator().migrate();
        // 已授权插件在装配完声明之后、任何工具调用之前装载（08-application §8.9.6）
        component.pluginAssembler().assembleAll();
        // 崩溃残留按 5 条规则补齐；会话可直接续跑（08-application §8.10）
        component.startupReconciler().reconcileStartup();
        // 启动打印生效值，一条日志解决"我改的怎么没生效"
        System.out.println("data-dir=" + dir.root()
                + "  log-level=" + options.logLevel()
                + "  maxReActRounds=" + component.policyRepository().load().maxReActRounds()
                + "  schemaVersion=1");
        warnMissingCredentials(component);
        component.tuiApp().run();
    }
}
