package zcd.jellyfish.cli.console;

/**
 * 外壳的输入输出面：CLI 的全部副作用都只经由这三个方法进出。
 * <p>
 * <b>为什么抽出接口</b>：CLI 的行为几乎都在「什么时候往哪个流写什么」。把输出面抽象出来后，
 * 单测可以用内存实现断言「回答去了 stdout、诊断去了 stderr」，而不必去截获真实的 {@code System.out}。
 * <p>
 * <b>为什么流式与整行分开</b>：思考过程、工具轨迹是<b>增量</b>回调（每次几十个字符），
 * 逐段追加换行会把输出打散；而工具进度、错误是<b>整行</b>诊断，天然该带换行。两类语义不同，
 * 接口上分开而不是靠调用方自己拼 {@code "\n"}。
 * <p>
 * <b>只服务单次模式</b>：交互式外壳（TUI）有自己的渲染层，不会复用本接口——因此这里不提供
 * 「读一行」这种交互式 API，避免为不存在的降级形态预留死代码。
 *
 * @author zcd
 */
public interface ConsoleIO {

    /**
     * 读取标准输入直到 EOF。
     * <p>
     * 单次模式在缺少 {@code -p} 时用它拿输入，因此「管道」与「手敲后按 Ctrl+D」两种用法都被覆盖。
     *
     * @return 读到的全部文本，保证非 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 读取失败时抛出
     */
    String readAll();

    /**
     * 向标准输出原样写出文本（不追加换行），并立即刷新。
     * <p>
     * 回答与命令结果走这里；这是「可被脚本消费」的那条流。回答由 {@code CliReActListener}
     * 按回合缓冲后整体写出，因此通常一次调用就是完整回答。
     *
     * @param text 文本，可为 {@code null}（忽略）
     */
    void writeOut(String text);

    /**
     * 向标准错误原样写出文本（不追加换行），并立即刷新。
     * <p>
     * 供思考过程这类流式诊断使用：它们的换行由内容自己带，不能由外壳追加。
     *
     * @param text 文本，可为 {@code null}（忽略）
     */
    void writeErr(String text);

    /**
     * 向标准错误写一整行诊断（自动换行）。
     * <p>
     * 工具进度、回合错误、占位提示走这里；这些内容与「回答」无关，因此不污染 stdout。
     *
     * @param line 整行文本，可为 {@code null}（按空行处理）
     */
    void writeErrLine(String line);
}
