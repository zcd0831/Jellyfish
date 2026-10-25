package io.github.zcd0831.jellyfish.boot;

/**
 * 启动参数：数据目录、日志级别、关闭颜色。
 */
public final class BootOptions {

    private final String dataDir;
    private final String logLevel;
    private final boolean noColor;

    private BootOptions(String dataDir, String logLevel, boolean noColor) {
        this.dataDir = dataDir;
        this.logLevel = logLevel;
        this.noColor = noColor;
    }

    public static BootOptions parse(String[] args) {
        String dataDir = System.getProperty("user.home") + "/.jellyfish";
        String logLevel = "info";
        boolean noColor = System.getenv("NO_COLOR") != null;
        for (int i = 0; i < args.length; i++) {
            if ("--data-dir".equals(args[i]) && i + 1 < args.length) {
                dataDir = args[++i];
            } else if ("--log-level".equals(args[i]) && i + 1 < args.length) {
                logLevel = args[++i];
            } else if ("--no-color".equals(args[i])) {
                noColor = true;
            } else {
                throw new IllegalArgumentException("未知参数：" + args[i]);
            }
        }
        if (!LOG_LEVELS.contains(logLevel)) {
            throw new IllegalArgumentException("--log-level 只能是 trace/debug/info/warn/error");
        }
        return new BootOptions(dataDir, logLevel, noColor);
    }

    private static final java.util.List<String> LOG_LEVELS =
            java.util.Arrays.asList("trace", "debug", "info", "warn", "error");

    public String dataDir() {
        return dataDir;
    }

    /** 关掉 ANSI 颜色：命令行显式指定或环境变量 `NO_COLOR`。 */
    public boolean noColor() {
        return noColor;
    }

    public String logLevel() {
        return logLevel;
    }
}
