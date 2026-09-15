package zcd.jellyfish.tui;

import java.util.Optional;

/**
 * TUI 的终端前置条件判定。
 * <p>
 * <b>为什么必须有这道检查</b>：没有可交互终端时 TUI <b>不会报错，而是永久挂住</b>。实测
 * （{@code java -jar ... -tui < /dev/null}）：JLine 检测不到终端，只往 stderr 打一行
 * {@code Unable to create a system terminal}，然后退化成 dumb 终端；TamboUI 照常进事件循环，
 * 卡在 {@code TuiRunner.pollEvent} 等一个永远不会来的事件。用户看到的是「黑屏 + 不退出」，
 * 完全无从判断是自己代码卡死还是环境不对——这正是最该被换成一句明确报错的场景。
 * <p>
 * <b>判据为什么用 {@code System.console()}</b>：它在两种环境下的取值实测正好对得上——
 * 真实终端（PTY）下是非 {@code null} 的 {@code java.io.Console}，管道 / 无终端时是 {@code null}。
 * 注意 JDK 8 的语义是「{@code stdin} <b>或</b> {@code stdout} 任一被重定向就返回 {@code null}」
 * （JDK 22 之后才改成只看是否 tty），因此 {@code -tui > log.txt} 也会被拦下。
 * 对 TUI 而言这本来就是坏用法——它两个流都要用，所以这个偏严格的行为是可以接受的。
 * <p>
 * <b>留一个显式的逃生门</b>：判据有可能误伤（异常终端、IDE 的伪终端实现差异），
 * 而误伤的代价是「完全用不了」。因此提供 {@value #SKIP_CHECK_PROPERTY} 让用户自己担责跳过；
 * 它是<b>显式且需自行承担后果</b>的，与「默默挂住」有本质区别。
 *
 * @author zcd
 */
public final class TuiTerminal {

    /**
     * 跳过终端检查的系统属性名。
     * <p>
     * 设为 {@code true} 时不再判定终端，直接放行；后果是终端不满足时仍然会挂住。
     */
    public static final String SKIP_CHECK_PROPERTY = "jellyfish.tui.skipTerminalCheck";

    private TuiTerminal() {
    }

    /**
     * 判断当前进程是否具备可交互终端。
     *
     * @return 可交互返回 {@code true}
     */
    public static boolean isInteractive() {
        if (Boolean.parseBoolean(System.getProperty(SKIP_CHECK_PROPERTY))) {
            return true;
        }
        return System.console() != null;
    }

    /**
     * 取不能运行 TUI 的原因，供外壳直接展示给用户。
     *
     * @return 可以运行返回 {@link Optional#empty()}；否则返回原因描述
     */
    public static Optional<String> unsupportedReason() {
        if (isInteractive()) {
            return Optional.empty();
        }
        return Optional.of("TUI 需要可交互终端，当前进程没有（标准输入或标准输出不是终端）。"
                + "请在真实终端里运行，或改用 -cli 做单次调用。"
                + "若确认终端可用仍被拦下，可加 -D" + SKIP_CHECK_PROPERTY + "=true 跳过检查。");
    }
}
