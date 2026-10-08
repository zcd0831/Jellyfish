package zcd.jellyfish.cli.console;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.support.ControlChars;

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
 * <b>为什么每次都 flush</b>：{@code System.out} 是行缓冲的，而思考过程、工具轨迹是不含换行的增量，
 * 不显式 flush 就会出现「模型早就想完了，屏幕上还是空的」。单次模式的性能开销无关紧要。
 * <p>
 * <b>为什么不关闭 {@code System.in}</b>：读完即关闭会让随后的任何读取直接失败，而这不是本对象的职责
 * （进程退出由 {@code main} 负责）。三参构造器供测试注入内存流。
 * <p>
 * <b>写出前一律过控制字符过滤</b>：这是外壳里唯一往终端写字节的地方，而写出去的文本几乎都来自
 * 不可信来源（模型回答、工具输出正文、异常消息、命令回显）。终端把 {@code ESC} 当控制序列引导符，
 * 一段 {@code ESC[2J} 就能清屏、{@code ESC]0;…BEL} 能改窗口标题——而 CLI 的屏幕上并没有审批框之类
 * 「显示的内容必须等于真正要执行的内容」的承诺，被改写的只是人读到的诊断，因此更要挡。
 * <b>过滤与「写到哪里去」无关</b>：stdout 被重定向时下游不是终端、{@code ESC} 没有攻击性，
 * 但 JDK 1.8 没有可靠的「这是不是终端」判定，且「有时过滤有时不过滤」会让行为随运行环境变化；
 * 判据取「文本从哪里来」更稳，代价是重定向到文件时回答里的控制字符也会被剔掉。
 * 整行诊断（{@code writeErrLine}）额外走 {@link ControlChars#singleLine}：单行语义下换行是注入手段。
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
     * 标准输入的字符读取器，懒建。
     * <p>
     * <b>两种读取方式必须共用同一个</b>：{@link InputStreamReader} 会预读一批字符进自己的缓冲，
     * 各建一个的话，第二个读到的内容会从第一个已经预读走的位置开始——表现为「确认框读到了空行」
     * 或「单次输入少了一截」。
     */
    private Reader reader;

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
        Reader source = reader();
        try {
            int read = source.read(buffer);
            while (read != -1) {
                text.append(buffer, 0, read);
                read = source.read(buffer);
            }
        } catch (IOException e) {
            throw new JellyfishException("读取 stdin 失败：" + e.getMessage(), e);
        }
        return text.toString();
    }

    @Override
    public String readLine() {
        Reader source = reader();
        StringBuilder text = new StringBuilder();
        try {
            int read = source.read();
            if (read == -1) {
                return null;
            }
            while (read != -1 && read != '\n') {
                text.append((char) read);
                read = source.read();
            }
        } catch (IOException e) {
            throw new JellyfishException("读取 stdin 失败：" + e.getMessage(), e);
        }
        // 兼容 CRLF：确认框只关心那一个字母，把回车留在里面会让所有选项都对不上
        int length = text.length();
        if (length > 0 && text.charAt(length - 1) == '\r') {
            text.setLength(length - 1);
        }
        return text.toString();
    }

    /**
     * 取标准输入的字符读取器，首次使用时创建。
     *
     * @return 读取器，保证非 {@code null}
     */
    private synchronized Reader reader() {
        if (reader == null) {
            reader = new InputStreamReader(in);
        }
        return reader;
    }

    @Override
    public void writeOut(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        out.print(ControlChars.strip(text));
        out.flush();
    }

    @Override
    public void writeErr(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        err.print(ControlChars.strip(text));
        err.flush();
    }

    @Override
    public void writeErrLine(String line) {
        // println(null) 会打出字面量 "null"；空行才是「没有内容」的正确表现
        err.println(line == null ? "" : ControlChars.singleLine(line));
        err.flush();
    }
}
