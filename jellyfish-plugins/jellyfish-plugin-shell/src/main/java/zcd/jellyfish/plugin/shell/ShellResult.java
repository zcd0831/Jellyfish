package zcd.jellyfish.plugin.shell;

import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一次命令执行的结论：退出码、终止原因、耗时，以及输出是不是二进制。
 * <p>
 * <b>非零退出码不是异常</b>：{@code grep} 没匹配到返回 1、{@code diff} 有差异返回 1，
 * 这些都是<b>信息</b>而不是故障。把它变成异常会把工具调用的失败语义稀释掉——
 * 模型与外壳都再也分不清「命令说了没有」与「命令根本没跑起来」。
 * <p>
 * <b>被终止时没有退出码</b>：进程是我们杀掉的，它的退出码（143 / 137）只反映我们发的信号，
 * 报出来只会让人误以为命令自己出了问题；此时如实给出终止原因。
 * <p>
 * 不可变。
 *
 * @author zcd
 */
final class ShellResult {

    /** 终止原因。 */
    enum Termination {

        /** 命令自己跑完了（退出码有意义）。 */
        COMPLETED,

        /** 达到墙上时钟超时。 */
        TIMEOUT,

        /** 达到静默超时：连续一段时间没有任何输出。 */
        IDLE_TIMEOUT,

        /** 用户取消（Esc / 客户端断连）。 */
        CANCELLED
    }

    /** 终止原因。 */
    private final Termination termination;

    /** 退出码，仅 {@link Termination#COMPLETED} 时非空。 */
    private final Integer exitCode;

    /** 耗时（毫秒）。 */
    private final long durationMillis;

    /** 输出是否被判定为二进制。 */
    private final boolean binaryOutput;

    /** 二进制输出时丢弃的字节数。 */
    private final long binaryBytes;

    /**
     * 构造结果。
     *
     * @param termination    终止原因
     * @param exitCode       退出码，可为 {@code null}
     * @param durationMillis 耗时（毫秒）
     * @param binaryOutput   是否二进制输出
     * @param binaryBytes    二进制输出时的字节数
     */
    ShellResult(Termination termination, Integer exitCode, long durationMillis, boolean binaryOutput,
                long binaryBytes) {
        this.termination = termination;
        this.exitCode = exitCode;
        this.durationMillis = durationMillis;
        this.binaryOutput = binaryOutput;
        this.binaryBytes = binaryBytes;
    }

    /**
     * 构造结果。
     *
     * @param termination    终止原因，不可为 {@code null}
     * @param exitCode       退出码，被终止时传 {@code null}
     * @param durationMillis 耗时（毫秒）
     * @param binaryOutput   输出是否被判定为二进制
     * @param binaryBytes    二进制输出时的字节数
     * @return 结果
     */
    static ShellResult of(Termination termination, Integer exitCode, long durationMillis,
                          boolean binaryOutput, long binaryBytes) {
        return new ShellResult(termination, exitCode, durationMillis, binaryOutput, binaryBytes);
    }

    /**
     * 获取终止原因。
     *
     * @return 终止原因
     */
    Termination termination() {
        return termination;
    }

    /**
     * 判断命令是否自己跑完且成功。
     *
     * @return 成功返回 {@code true}
     */
    boolean isSuccess() {
        return termination == Termination.COMPLETED && exitCode != null && exitCode.intValue() == 0;
    }

    /**
     * 渲染「始终可见」的元数据行。
     * <p>
     * 它会成为输出正文的首行，因此两条路径（未溢出、溢出成信封）里位置一致：模型永远能在同一个地方
     * 找到「这条命令在哪跑的、结果如何」。工作目录必须在这里报出来——审批者与模型都靠它判断
     * 「这条命令动的是哪个目录」。
     *
     * @param workingDirectory 工作目录文本，可为 {@code null}
     * @return 元数据行，保证非 {@code null}
     */
    /**
     * 构造结构化元数据：与 {@link #summary(String)} 同一份事实的机器可读版本。
     * <p>
     * <b>只有两个键是内核约定认识的</b>（退出码、终止原因），其余是本工具自己的（耗时、二进制字节数）。
     * 内核只透传不解释，界面按 {@code ToolMetadata#failed} 判「值不值得警示」。
     * <p>
     * <b>没正常跑完时不填退出码</b>：那时进程的退出码只反映我们发的信号（143 / 137），
     * 报出来会被读成「命令自己出了问题」——与 {@code summary} 不报退出码是同一条理由。
     *
     * @return 元数据，保证非 {@code null}
     */
    Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (termination == Termination.COMPLETED) {
            metadata.put(ToolMetadata.KEY_EXIT_CODE, exitCode);
        }
        metadata.put(ToolMetadata.KEY_TERMINAL, termination.name());
        metadata.put("durationMs", durationMillis);
        if (binaryOutput) {
            metadata.put("binary", Boolean.TRUE);
            metadata.put("binaryBytes", binaryBytes);
        }
        return metadata;
    }

    String summary(String workingDirectory) {
        StringBuilder text = new StringBuilder();
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            text.append("cwd: ").append(workingDirectory).append(" · ");
        }
        switch (termination) {
            case COMPLETED:
                text.append("exit: ").append(exitCode);
                break;
            case TIMEOUT:
                text.append("已超时（超过 ").append(seconds(durationMillis)).append(" 秒），已终止");
                break;
            case IDLE_TIMEOUT:
                text.append("连续 ").append(seconds(durationMillis)).append(" 秒无输出，判定为卡住并终止");
                break;
            case CANCELLED:
                text.append("已取消，进程已终止");
                break;
            default:
                break;
        }
        text.append(" · 耗时: ").append(formatSeconds(durationMillis)).append(" 秒");
        if (binaryOutput) {
            text.append(" · 二进制输出，").append(binaryBytes).append(" 字节已省略");
        }
        return text.toString();
    }

    /**
     * 把毫秒换算成整秒。
     *
     * @param millis 毫秒
     * @return 秒数，至少为 0
     */
    private static long seconds(long millis) {
        return Math.max(0L, millis / 1000L);
    }

    /**
     * 把毫秒格式化成一位小数的秒。
     * <p>
     * 显式指定 {@link Locale#ROOT}：某些区域的默认小数点是逗号，
     * 那会让「耗时: 1,2 秒」这种读起来像千位分隔的数字混进上下文。
     *
     * @param millis 毫秒
     * @return 形如 {@code 1.2} 的文本
     */
    private static String formatSeconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0d);
    }
}
