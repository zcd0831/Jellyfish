package zcd.jellyfish.cli;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.cli.console.ConsoleIO;
import zcd.jellyfish.cli.console.SystemConsoleIO;
import zcd.jellyfish.cli.di.DaggerJellyfishComponent;

/**
 * 进程入口：解析启动参数 → 组装 → 按模式运行 → 以退出码结束进程。
 * <p>
 * <b>本类只做四件事</b>：解析参数、处理 {@code -h} / {@code -V}、设置日志级别、把退出码交给
 * {@code System.exit}。任何模式相关的判断都在 {@link Launcher} 与各 {@code RunMode} 里，
 * 这样新增模式时入口不需要改。
 * <p>
 * <b>为什么不在这里持有静态 Logger</b>：{@code --verbose} 需要在 Log4j2 读取配置<b>之前</b>把级别写进系统属性，
 * 而类里一旦有待静态初始化的 {@code Logger} 字段，类加载本身就会提前碰日志框架（属性还没设，级别就定了）。
 * 因此本类不记日志，错误直接写 stderr。
 * <p>
 * <b>为什么参数要扫描两遍</b>：第一遍只看 {@code --verbose}（必须早于日志初始化），第二遍才真正解析语义。
 * 多扫一遍数组的代价可以忽略，换来的是「详细日志一定生效」这条不依赖时序假设的保证。
 *
 * @author zcd
 */
public final class JellyfishApplication {

    /** 程序名，用于用法与版本输出。 */
    private static final String PROGRAM_NAME = "jellyfish";

    /** 版本号兜底值：从 classes 目录直接运行时 manifest 里没有版本信息。 */
    private static final String VERSION_FALLBACK = "development";

    /** 日志级别系统属性名，由 {@code log4j2.xml} 读取。 */
    private static final String LOG_LEVEL_PROPERTY = "jellyfish.log.level";

    /** Log4j2 配置选择属性名。 */
    private static final String LOG_CONFIG_PROPERTY = "log4j.configurationFile";

    /** TUI 模式使用的日志配置：输出到文件，不碰 stderr（stderr 在备用屏期间会撕坏画面）。 */
    private static final String TUI_LOG_CONFIG = "log4j2-tui.xml";

    /** 详细日志旗标。 */
    private static final String FLAG_VERBOSE = "--verbose";

    /** 详细日志级别取值。 */
    private static final String LEVEL_DEBUG = "DEBUG";

    private JellyfishApplication() {
    }

    /**
     * 进程入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        applyVerbosity(args);
        System.exit(run(args, new SystemConsoleIO()));
    }

    /**
     * 执行一次完整的启动流程并返回退出码。
     * <p>
     * 与 {@link #main(String[])} 分开是为了能在不结束 JVM 的前提下测试参数处理路径。
     *
     * @param args    命令行参数
     * @param console 输出面板
     * @return 退出码，取值见 {@link ExitCodes}
     */
    static int run(String[] args, ConsoleIO console) {
        StartupOptions options;
        try {
            options = StartupOptionsParser.parse(args);
        } catch (JellyfishException e) {
            console.writeErrLine("错误：" + e.getMessage());
            console.writeErrLine(StartupOptionsParser.usage());
            return ExitCodes.USAGE_ERROR;
        }
        applyLogTarget(options);
        if (options.isHelp()) {
            console.writeOut(StartupOptionsParser.usage() + "\n");
            return ExitCodes.OK;
        }
        if (options.isVersion()) {
            console.writeOut(PROGRAM_NAME + " " + version() + "\n");
            return ExitCodes.OK;
        }
        try {
            return new Launcher(DaggerJellyfishComponent.create(), console).launch(options);
        } catch (RuntimeException e) {
            // DI 装配或组件创建失败：此刻还没有 harness 可收敛，只能报错退出
            console.writeErrLine("初始化失败：" + e.getMessage());
            return ExitCodes.STARTUP_ERROR;
        }
    }

    /**
     * 若命令行要求详细日志，则把级别写进系统属性。
     * <p>
     * 只改属性、不编程式操作日志实现：{@code log4j2.xml} 里用 {@code ${sys:jellyfish.log.level:-WARN}} 读取，
     * 这样业务代码里不出现任何日志框架的实现类。也可直接用 {@code -Djellyfish.log.level=DEBUG} 覆盖。
     *
     * @param args 命令行参数，可为 {@code null}
     */
    private static void applyVerbosity(String[] args) {
        if (args == null) {
            return;
        }
        for (String arg : args) {
            if (FLAG_VERBOSE.equals(arg)) {
                System.setProperty(LOG_LEVEL_PROPERTY, LEVEL_DEBUG);
                return;
            }
        }
    }

    /**
     * 若启动参数要求 TUI 模式，则把日志输出目标切到文件。
     * <p>
     * <b>为什么必须在 DI 装配之前做</b>：Log4j2 在<b>第一个 Logger 被创建</b>时读取配置并固定下来，
     * 而 {@code DaggerJellyfishComponent.create()} 之后的调用链（配置加载、插件运行时）就会创建 Logger。
     * 因此设置系统属性必须发生在那之前，否则后到的设置不生效，日志继续写 stderr、继续撕坏画面。
     * <p>
     * <b>为什么不编程式操作 Appenders</b>：业务代码里出现日志框架实现类会把这些类硬绑到 Log4j2 上，
     * 也就再也不能换 binding。只设一个系统属性，切换点归配置文件管。
     *
     * @param options 启动参数，可为 {@code null}
     */
    /**
     * 把日志从终端切到文件。
     * <p>
     * 必须在<b>第一个 Logger 被创建之前</b>调用（即 DI 装配之前），否则配置已经初始化、再改不生效。
     * <p>
     * 不需要预先建目录：实测 Log4j2 会自建缺失的多级父目录（{@code -Djellyfish.log.file=/tmp/a/b/c.log}
     * 能把 {@code a/b} 一并建出来）。
     *
     * @param options 启动参数，可为 {@code null}
     */
    private static void applyLogTarget(StartupOptions options) {
        if (options == null || options.getMode() != StartupOptions.Mode.TUI) {
            return;
        }
        System.setProperty(LOG_CONFIG_PROPERTY, TUI_LOG_CONFIG);
    }

    /**
     * 读取版本号。
     * <p>
     * 优先取 jar manifest 的 {@code Implementation-Version}（打包时由构建写入），取不到时回退到常量——
     * 刻意不用资源过滤生成版本文件，那会把 {@code ${}} 替换规则扩散到 {@code config.json}，
     * 与配置层「环境变量占位符」的语法打架。
     *
     * @return 版本号，保证非空
     */
    private static String version() {
        Package applicationPackage = JellyfishApplication.class.getPackage();
        String version = applicationPackage == null ? null : applicationPackage.getImplementationVersion();
        return version == null || version.isEmpty() ? VERSION_FALLBACK : version;
    }
}
