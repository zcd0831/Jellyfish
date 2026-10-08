package zcd.jellyfish.cli.console;

/**
 * 测试用的内存 {@link ConsoleIO}：把三个流各自的写入原样记下来，供断言分流是否正确。
 * <p>
 * 刻意手写而不是 mock：断言的对象是「哪个流收到了什么」，用记录器读起来比 {@code verify} 更直观，
 * 也便于同时检查多次写入的拼接结果。与 infra 的 {@code LlmClientTestSupport} 同属测试支撑类。
 *
 * @author zcd
 */
public final class RecordingConsoleIO implements ConsoleIO {

    /** 预置的标准输入内容。 */
    private final String stdin;

    /** 标准输出累计内容。 */
    private final StringBuilder out = new StringBuilder();

    /** 标准错误累计内容。 */
    private final StringBuilder err = new StringBuilder();

    /** 标准错误写入的整行条数。 */
    private int errorLineCount;

    /** 预置的「读一行」应答队列（按顺序消费，耗尽后返回 EOF）。 */
    private final java.util.Deque<String> lines = new java.util.ArrayDeque<String>();

    /**
     * 构造记录器。
     *
     * @param stdin 预置的标准输入内容，可为 {@code null}（等价空串）
     */
    public RecordingConsoleIO(String stdin) {
        this.stdin = stdin == null ? "" : stdin;
    }

    /**
     * 追加一条「读一行」的应答，供确认框这类逐行读取的用例使用。
     *
     * @param line 应答内容，可为 {@code null}（表示 EOF）
     * @return 本记录器
     */
    public RecordingConsoleIO withLine(String line) {
        lines.addLast(line);
        return this;
    }

    @Override
    public String readAll() {
        return stdin;
    }

    @Override
    public String readLine() {
        return lines.isEmpty() ? null : lines.pollFirst();
    }

    @Override
    public void writeOut(String text) {
        if (text != null) {
            out.append(text);
        }
    }

    @Override
    public void writeErr(String text) {
        if (text != null) {
            err.append(text);
        }
    }

    @Override
    public void writeErrLine(String line) {
        errorLineCount++;
        err.append(line).append('\n');
    }

    /**
     * 获取标准输出累计内容。
     *
     * @return 标准输出内容
     */
    public String out() {
        return out.toString();
    }

    /**
     * 获取标准错误累计内容。
     *
     * @return 标准错误内容
     */
    public String err() {
        return err.toString();
    }

    /**
     * 获取标准错误写入的整行条数。
     *
     * @return 整行条数
     */
    public int errorLineCount() {
        return errorLineCount;
    }
}
