package io.github.zcd0831.jellyfish.boot;

/**
 * 只有两个启动参数：数据目录与日志级别（技术决策 D12）。
 */
public final class BootOptions {

    private final String dataDir;
    private final String logLevel;

    private BootOptions(String dataDir, String logLevel) {
        this.dataDir = dataDir;
        this.logLevel = logLevel;
    }

    public static BootOptions parse(String[] args) {
        String dataDir = System.getProperty("user.home") + "/.jellyfish";
        String logLevel = "info";
        for (int i = 0; i < args.length; i++) {
            if ("--data-dir".equals(args[i]) && i + 1 < args.length) {
                dataDir = args[++i];
            } else if ("--log-level".equals(args[i]) && i + 1 < args.length) {
                logLevel = args[++i];
            } else {
                throw new IllegalArgumentException("未知参数：" + args[i]);
            }
        }
        if (!LOG_LEVELS.contains(logLevel)) {
            throw new IllegalArgumentException("--log-level 只能是 trace/debug/info/warn/error");
        }
        return new BootOptions(dataDir, logLevel);
    }

    private static final java.util.List<String> LOG_LEVELS =
            java.util.Arrays.asList("trace", "debug", "info", "warn", "error");

    public String dataDir() {
        return dataDir;
    }

    public String logLevel() {
        return logLevel;
    }
}
