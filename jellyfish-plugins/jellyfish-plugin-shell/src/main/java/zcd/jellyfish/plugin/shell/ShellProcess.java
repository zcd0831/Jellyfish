package zcd.jellyfish.plugin.shell;

/**
 * 已启动子进程的句柄（接缝）。
 * <p>
 * <b>为什么要有这个接口</b>：超时、静默判定、终止链与取消这四件事全都在
 * {@link ShellProcessRunner} 的等待循环里，而它们恰恰是最需要单测的部分。若直接依赖
 * {@link Process}，单测就必须真的 fork 进程（慢、依赖平台、还可能在 CI 上留下残留进程）。
 * 因此把「进程」抽成一个接口：真实实现包 {@code Process}，假实现由测试驱动状态变化。
 * <p>
 * 实现方需要保证方法可被多个线程调用（取消回调在工具线程上、等待循环在另一个线程上）。
 *
 * @author zcd
 */
interface ShellProcess {

    /**
     * 判断进程是否仍在运行。
     *
     * @return 仍在运行返回 {@code true}
     */
    boolean isAlive();

    /**
     * 等待进程退出。
     *
     * @param millis 最多等待的毫秒数
     * @return 进程已退出返回 {@code true}
     * @throws InterruptedException 等待被中断时抛出
     */
    boolean waitFor(long millis) throws InterruptedException;

    /**
     * 获取退出码。
     *
     * @return 退出码；进程仍在运行时结果未定义
     */
    int exitValue();

    /**
     * 获取进程标识，用于尽力杀掉整棵进程树。
     *
     * @return 进程标识；取不到时返回 {@code -1}（此时退化为只能杀直接子进程）
     */
    long pid();

    /**
     * 发送温和终止信号（POSIX 上是 SIGTERM）。
     * <p>
     * 这是取消回调里唯一允许做的动作：它可能在界面渲染线程上执行，因此不能等待、不能递归。
     */
    void destroy();

    /**
     * 强杀进程（POSIX 上是 SIGKILL）。
     */
    void destroyForcibly();

    /**
     * 关闭子进程的标准输入，让读 stdin 的命令立即看到 EOF。
     * <p>
     * 命令行工具不需要 stdin：交互式命令（{@code vi} / {@code ssh} / {@code sudo}）必须快速失败，
     * 而且它们绝不能抢终端——TUI 处于 raw 模式，子进程直接写终端会把界面画烂。
     */
    void closeStdin();

    /**
     * 等待输出泵结束，保证子进程写出的最后一段内容已经进入捕获通道。
     * <p>
     * 不等它就直接收尾，会丢掉输出的最后一截——而那往往正是命令失败的原因。
     *
     * @param millis 最多等待的毫秒数
     */
    void awaitOutput(long millis);
}
