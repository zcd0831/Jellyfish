package zcd.jellyfish.api.extension;

/**
 * 工具输出捕获：内核交给工具的一条「把输出写进来」的通道。
 * <p>
 * <b>解决什么问题</b>：有些工具的输出是<b>无界且不可再取</b>的——命令行的输出只在管道里出现一次，
 * 进程结束就没了（不像文件还能按 {@code offset} 重读）。把这种输出整份物化进内存会撑爆 JVM，
 * 而工具层自己截断又会让被丢掉的部分<b>永久丢失</b>。本接口让内核在捕获过程中就接管这件事：
 * 工具只管往里写，内核负责「攒到阈值之前留在内存、超过阈值转写磁盘、回灌时只给头尾预览」。
 * <p>
 * <b>为什么由内核实现</b>：插件只依赖 {@code jellyfish-api}，拿不到内核的落盘存储。若让插件自己写文件，
 * 就会出现第二套落盘目录、命名、清理与提示格式，直接违反「同一份知识不写两遍」。交给内核实现后，
 * 工具<b>看不到路径、目录、清理策略与信封格式</b>，它只知道「写进来」。
 * <p>
 * <b>不使用本通道的工具</b>：拿到的 {@link #NOOP}，返回对象照旧走
 * {@code ToolOutputLimiter} 的事后截断路径，行为与引入本接口之前完全一致。
 * <p>
 * <b>线程语义</b>：{@link #write(String)} 可能被<b>多条线程并发调用</b>（命令行的 stdout 与 stderr
 * 由两条泵线程各自读取），实现必须线程安全。
 *
 * @author zcd
 */
public interface ToolOutputSink {

    /**
     * 不使用捕获通道的工具拿到的实例：吞掉全部写入，{@link #finish()} 恒返回 {@code null}。
     */
    ToolOutputSink NOOP = new ToolOutputSink() {

        @Override
        public void write(String chunk) {
            // 不使用捕获：丢弃
        }

        @Override
        public void summary(String text) {
            // 不使用捕获：丢弃
        }

        @Override
        public String finish() {
            return null;
        }
    };

    /**
     * 追加一段输出。
     * <p>
     * 实现必须线程安全，且必须<b>持续接受</b>写入——即使已经放弃保留内容也要照常返回：
     * 停止读取会让正在写管道的子进程永久阻塞，表现为「命令卡死」。
     *
     * @param chunk 输出片段，可为 {@code null} 或空串（实现按无内容处理）
     */
    void write(String chunk);

    /**
     * 声明一条「始终可见」的元数据行（工作目录、退出码、耗时……）。
     * <p>
     * 渲染在正文首行：头尾切分保留头部，因此它不会被截断丢掉。可多次调用，按调用顺序拼接。
     *
     * @param text 元数据文本，可为 {@code null} 或空串（实现按无内容处理）
     */
    void summary(String text);

    /**
     * 结束捕获并返回最终文本。
     * <p>
     * <b>幂等</b>：内核会在工具调用返回后的 {@code finally} 里兜底再调一次，用于保证落盘句柄释放；
     * 重复调用必须返回同一份文本（而不是追加或报错）。
     * <p>
     * 返回内容分两种形态：
     * <ul>
     *     <li>未超过预算：纯文本（元数据首行 + 全部输出），不产生任何文件；</li>
     *     <li>超过预算：截断信封（含头尾预览、总量、落盘路径与回查指引）。</li>
     * </ul>
     *
     * @return 最终文本；从未捕获到任何内容时返回 {@code null}
     */
    String finish();
}
