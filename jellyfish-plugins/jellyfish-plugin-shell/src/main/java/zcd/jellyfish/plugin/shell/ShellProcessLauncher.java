package zcd.jellyfish.plugin.shell;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 启动子进程的接缝。
 * <p>
 * 与 {@link ShellProcess} 同一个理由：执行层不该知道进程是怎么起来的。真实实现走
 * Apache Commons Exec，测试用假实现直接往输出流写几段文本并置状态——这样「超时怎么判、
 * 取消怎么杀、输出怎么进 sink」都能在没有真实进程的情况下被断言。
 *
 * @author zcd
 */
interface ShellProcessLauncher {

    /**
     * 启动进程，并把合并后的输出写入 {@code mergedOutput}。
     * <p>
     * stdout 与 stderr 必须<b>合并成一条流</b>，顺序为到达顺序：像终端一样。
     * 分节显示需要引入 channel 概念，而收益只是「看起来整齐一点」。
     *
     * @param invocation   调用参数，不可为 {@code null}
     * @param mergedOutput 合并输出的写入目标，不可为 {@code null}
     * @return 进程句柄，保证非 {@code null}
     * @throws IOException 进程无法启动时抛出（命令解释器不存在、目录不可访问等）
     */
    ShellProcess launch(ShellInvocation invocation, OutputStream mergedOutput) throws IOException;
}
