package zcd.jellyfish.plugin.shell;

import java.nio.file.Path;
import java.util.Map;

/**
 * 一次命令调用的完整参数：由 {@link ShellTool} 把「工具参数 + 插件配置」合并成它，再交给
 * {@link ShellProcessRunner} 执行。
 * <p>
 * <b>为什么要有这个中间对象</b>：合并的规则（超时怎么钳制、环境怎么叠加、cwd 怎么解析）是
 * 工具层的事，而执行层只该关心「跑什么、跑多久、在哪跑」。把两者分开之后，
 * 执行层的单测不必去构造插件配置与模型参数。
 * <p>
 * 不可变。
 *
 * @author zcd
 */
final class ShellInvocation {

    /** 命令原文。 */
    private final String command;

    /** 工作目录。 */
    private final Path workingDirectory;

    /** 墙上时钟超时（毫秒），保证大于 0。 */
    private final long timeoutMillis;

    /** 静默超时（毫秒），{@code 0} 表示关闭。 */
    private final long idleTimeoutMillis;

    /** 子进程环境变量（完整的一份，不是增量）。 */
    private final Map<String, String> environment;

    /**
     * 构造调用参数。
     *
     * @param command          命令原文，不可为 {@code null}
     * @param workingDirectory 工作目录，不可为 {@code null}
     * @param timeoutMillis    墙上时钟超时（毫秒），保证大于 0
     * @param idleTimeoutMillis 静默超时（毫秒），{@code 0} 表示关闭
     * @param environment      子进程环境变量，不可为 {@code null}
     */
    ShellInvocation(String command, Path workingDirectory, long timeoutMillis,
                    long idleTimeoutMillis, Map<String, String> environment) {
        this.command = command;
        this.workingDirectory = workingDirectory;
        this.timeoutMillis = timeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.environment = environment;
    }

    /**
     * 获取命令原文。
     *
     * @return 命令原文
     */
    String command() {
        return command;
    }

    /**
     * 获取工作目录。
     *
     * @return 工作目录
     */
    Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * 获取墙上时钟超时（毫秒）。
     *
     * @return 超时毫秒数
     */
    long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 获取静默超时（毫秒）。
     *
     * @return 静默超时毫秒数，{@code 0} 表示关闭
     */
    long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    /**
     * 获取子进程环境变量。
     *
     * @return 环境变量映射
     */
    Map<String, String> environment() {
        return environment;
    }
}
