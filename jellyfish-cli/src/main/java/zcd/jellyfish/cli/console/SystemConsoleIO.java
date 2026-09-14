package zcd.jellyfish.cli.console;

import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.util.Objects;

/**
 * {@link ConsoleIO} 的生产实现：直接绑 {@code System.in} / {@code System.out} / {@code System.err}。
 * <p>
 * <b>为什么不指定字符集</b>：终端编码由运行环境决定（{@code -Dfile.encoding}），在这里硬编码 UTF-8 会在
 * 编码不匹配的平台上把中文变成乱码；交给 JDK 的默认字符集才是与终端一致的做法。
 * <p>
 * <b>为什么每次都 flush</b>：{@code System.out} 是行缓冲的，而流式回答是不含换行的增量，
 * 不显式 flush 就会出现「模型早就答完了，屏幕上还是空的」。单次模式的性能开销无关紧要。
 * <p>
 * <b>为什么不关闭 {@code System.in}</b>：读完即关闭会让随后的任何读取直接失败，而这不是本对象的职责
 * （进程退出由 {@code main} 负责）。三参构造器供测试注入内存流。
 *
 * @author zcd
 */
public final class SystemConsoleIO implements ConsoleIO {

    /** 读取缓冲区大小。 */
    private static final int READ_BUFFER_SIZE = 4096;

    /** 标准输入。 */
    private final InputStream in;

    /** 标准输出。 */
    private final PrintStream out;

    /** 标准错误。 */
    private final PrintStream err;

    /**
     * 构造标准流实现。
     */
    public SystemConsoleIO() {
        this(System.in, System.out, System.err);
    }

    /**
     * 构造可注入的实现，供测试使用。
     *
     * @param in  输入流，不可为 {@code null}
     * @param out 输出流，不可为 {@code null}
     * @param err 错误流，不可为 {@code null}
     */
    public SystemConsoleIO(InputStream in, PrintStream out, PrintStream err) {
        this.in = Objects.requireNonNull(in, "in must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.err = Objects.requireNonNull(err, "err must not be null");
    }

    @Override
    public String readAll() {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[READ_BUFFER_SIZE];
        Reader reader = new InputStreamReader(in);
        try {
            int read = reader.read(buffer);
            while (read != -1) {
                text.append(buffer, 0, read);
                read = reader.read(buffer);
            }
        } catch (IOException e) {
            throw new JellyfishException("读取 stdin 失败：" + e.getMessage(), e);
        }
        return text.toString();
    }

    @Override
    public void writeOut(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        out.print(text);
        out.flush();
    }

    @Override
    public void writeErr(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        err.print(text);
        err.flush();
    }

    @Override
    public void writeErrLine(String line) {
        // println(null) 会打出字面量 "null"；空行才是「没有内容」的正确表现
        err.println(line == null ? "" : line);
        err.flush();
    }
}
