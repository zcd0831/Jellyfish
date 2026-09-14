package zcd.jellyfish.cli.console;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SystemConsoleIO} 的单元测试：用内存流验证读取到 EOF 与三个流的写法。
 *
 * @author zcd
 */
class SystemConsoleIOTest {

    /** 输出流累计器。 */
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** 错误流累计器。 */
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void readAll_should_return_everything_until_eof() {
        SystemConsoleIO console = consoleWithInput("第一行\n第二行\n");

        assertEquals("第一行\n第二行\n", console.readAll());
    }

    @Test
    void readAll_should_return_empty_string_when_no_input() {
        SystemConsoleIO console = consoleWithInput("");

        assertEquals("", console.readAll());
    }

    @Test
    void readAll_should_return_empty_string_when_already_at_eof() {
        SystemConsoleIO console = consoleWithInput("abc");

        assertEquals("abc", console.readAll());
        assertEquals("", console.readAll());
    }

    @Test
    void readAll_should_wrap_io_failure() {
        SystemConsoleIO console = new SystemConsoleIO(failingInput(), new PrintStream(out), new PrintStream(err));

        assertThrows(zcd.jellyfish.api.JellyfishException.class, console::readAll);
    }

    @Test
    void writeOut_should_write_verbatim_without_newline() {
        consoleWithInput("").writeOut("回答");

        assertEquals("回答", text(out));
        assertEquals("", text(err));
    }

    @Test
    void writeOut_should_ignore_null_and_empty() {
        SystemConsoleIO console = consoleWithInput("");

        console.writeOut(null);
        console.writeOut("");

        assertEquals("", text(out));
    }

    @Test
    void writeErr_should_write_verbatim_without_newline() {
        consoleWithInput("").writeErr("诊断");

        assertEquals("诊断", text(err));
        assertEquals("", text(out));
    }

    @Test
    void writeErr_should_ignore_null_and_empty() {
        SystemConsoleIO console = consoleWithInput("");

        console.writeErr(null);
        console.writeErr("");

        assertEquals("", text(err));
    }

    @Test
    void writeErrLine_should_append_newline() {
        consoleWithInput("").writeErrLine("工具完成");

        assertEquals("工具完成" + System.lineSeparator(), text(err));
        assertEquals("", text(out));
    }

    @Test
    void writeErrLine_should_tolerate_null() {
        consoleWithInput("").writeErrLine(null);

        assertEquals(System.lineSeparator(), text(err));
    }

    @Test
    void constructor_should_reject_null_streams() {
        PrintStream printStream = new PrintStream(out);

        assertThrows(NullPointerException.class,
                () -> new SystemConsoleIO(null, printStream, printStream));
        assertThrows(NullPointerException.class,
                () -> new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), null, printStream));
        assertThrows(NullPointerException.class,
                () -> new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), printStream, null));
    }

    /**
     * 构造绑内存流的实现。
     *
     * @param input 预置输入
     * @return 实现
     */
    private SystemConsoleIO consoleWithInput(String input) {
        return new SystemConsoleIO(new ByteArrayInputStream(input.getBytes(Charset.defaultCharset())),
                new PrintStream(out, true), new PrintStream(err, true));
    }

    /**
     * 把累计器内容按默认字符集转成文本。
     *
     * @param stream 累计器
     * @return 文本
     */
    private static String text(ByteArrayOutputStream stream) {
        return new String(stream.toByteArray(), Charset.defaultCharset());
    }

    /**
     * 构造一个读取即失败的输入流。
     *
     * @return 输入流
     */
    private static InputStream failingInput() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };
    }
}
