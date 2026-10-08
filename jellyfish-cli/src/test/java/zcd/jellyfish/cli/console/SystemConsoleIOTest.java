package zcd.jellyfish.cli.console;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void writeOut_should_strip_escape_so_answer_cannot_clear_screen() {
        // 模型回答直接写在终端上：一段 ESC[2J 就能清屏、ESC]0;…BEL 能改窗口标题
        consoleWithInput("").writeOut("答案\u001b[2J尾部");

        assertEquals("答案[2J尾部", text(out));
        assertFalse(text(out).contains("\u001b"));
    }

    @Test
    void writeErr_should_strip_escape_from_tool_output() {
        // 工具输出正文来自磁盘上的文件：读到含 ESC 的文件就会把它打进终端
        consoleWithInput("").writeErr("日志\u001b]0;改标题\u0007行");

        assertEquals("日志]0;改标题行", text(err));
        assertFalse(text(err).contains("\u001b"));
    }

    @Test
    void writeErr_should_keep_newline_because_streamKeepsItsOwnLineBreaks() {
        // 增量诊断的换行由内容自己带，绝不能在这里换掉（否则思考过程会挤成一行）
        consoleWithInput("").writeErr("第一段\n第二段");

        assertEquals("第一段\n第二段", text(err));
    }

    @Test
    void writeErrLine_should_flatten_newline_because_oneLine_means_oneLine() {
        // 整行语义下换行是注入手段：一条含 \n 的理由能伪装成两行诊断
        consoleWithInput("").writeErrLine("回合被拦下：没事\n已授权：全部工具");

        assertEquals("回合被拦下：没事 已授权：全部工具" + System.lineSeparator(), text(err));
    }

    @Test
    void writeErrLine_should_strip_carriageReturn_so_line_cannot_be_rewritten() {
        // \r 能把已显示的一行原地改写：终端上看起来就像外壳自己打了那句话
        consoleWithInput("").writeErrLine("进度 10%\r进度 100%");

        assertEquals("进度 10%进度 100%" + System.lineSeparator(), text(err));
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
