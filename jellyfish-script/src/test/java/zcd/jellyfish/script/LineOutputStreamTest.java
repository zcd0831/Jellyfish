package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 行切分输出流的单元测试。
 * <p>
 * 本类最容易出错的不是「按行切」，而是<b>字节与字符的分界</b>：{@code OutputStream} 给的是字节，
 * 而一个 UTF-8 汉字的三个字节完全可能被拆到两次 {@code write} 里。按块解码会在字符中间断开，
 * 逐字节转字符会直接把中文变成乱码——两种错误都只在日志里出现，而那正是排查问题时唯一的线索。
 *
 * @author zcd
 */
@DisplayName("行切分输出流")
class LineOutputStreamTest {

    /** 收到的行。 */
    private final List<String> lines = new ArrayList<String>();

    /** 被测流。 */
    private CommonsExecScriptProcess.LineOutputStream stream =
            new CommonsExecScriptProcess.LineOutputStream(lines::add);

    @Test
    @DisplayName("应按换行切分并丢弃换行符")
    void write_should_splitByNewline() throws IOException {
        writeAll("第一行\n第二行\n");

        assertEquals(java.util.Arrays.asList("第一行", "第二行"), lines);
    }

    @Test
    @DisplayName("回车换行应被当作一次换行，不产生空行")
    void write_should_treatCarriageReturnAsLineEnding() throws IOException {
        writeAll("a\r\nb\r\n");

        assertEquals(java.util.Arrays.asList("a", "b"), lines);
    }

    @Test
    @DisplayName("空行应被丢弃，不交给接收方")
    void write_should_dropBlankLines() throws IOException {
        writeAll("a\n\nb\n");

        assertEquals(java.util.Arrays.asList("a", "b"), lines);
    }

    @Test
    @DisplayName("没有换行结尾的末行应在关闭时补发")
    void close_should_flushTrailingLineWithoutNewline() throws IOException {
        writeAll("最后一行没有换行");

        assertEquals(0, lines.size());
        stream.close();
        assertEquals(java.util.Collections.singletonList("最后一行没有换行"), lines);
    }

    @Test
    @DisplayName("跨多次写入的多字节字符应完整解码，不能被撕成乱码")
    void write_should_decodeMultiByteCharactersSplitAcrossWrites() throws IOException {
        byte[] payload = "中文✓".getBytes(StandardCharsets.UTF_8);
        // 逐字节写入：任何按块解码的实现都会在这里露出破绽
        for (byte value : payload) {
            stream.write(value & 0xFF);
        }
        stream.write('\n');

        assertEquals(java.util.Collections.singletonList("中文✓"), lines);
    }

    @Test
    @DisplayName("超长无换行输出应按上限截断，不能无限占用内存")
    void write_should_truncateOverlongLine() throws IOException {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 70000; index++) {
            builder.append('x');
        }
        writeAll(builder.toString());

        assertEquals(1, lines.size());
        assertEquals(64 * 1024, lines.get(0).length());
    }

    @Test
    @DisplayName("关闭后再写入应被忽略，不产生半行")
    void write_should_ignoreBytesAfterClose() throws IOException {
        writeAll("a\n");
        stream.close();

        writeAll("b\n");

        assertEquals(java.util.Collections.singletonList("a"), lines);
    }

    /**
     * 逐字节写入一段文本。
     *
     * @param text 文本
     * @throws IOException 写入失败时抛出
     */
    private void writeAll(String text) throws IOException {
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            stream.write(value & 0xFF);
        }
    }
}
