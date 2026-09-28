package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellOutputCapture} 的单元测试。
 * <p>
 * 三个关注点各对应一类真实故障：跨块解码（中文日志变乱码）、二进制识别（把图片灌进上下文）、
 * 收尾冲残余（最后半个字符丢掉）。
 *
 * @author zcd
 */
@DisplayName("ShellOutputCapture")
class ShellOutputCaptureTest {

    @Test
    @DisplayName("普通文本原样进捕获通道")
    void write_should_passTextThrough() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);

        write(capture, "hello\nworld\n");
        capture.close();

        assertEquals("hello\nworld\n", sink.body());
        assertFalse(capture.isBinary());
    }

    @Test
    @DisplayName("多字节字符被切开时仍能正确解码——中文日志变乱码是最难归因的一类问题")
    void write_should_decodeAcrossChunkBoundary() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);
        byte[] bytes = "构建失败\n".getBytes(StandardCharsets.UTF_8);

        // 逐字节写入：每个汉字都被切成了三块
        for (byte value : bytes) {
            capture.write(new byte[] {value}, 0, 1);
        }
        capture.close();

        assertEquals("构建失败\n", sink.body());
        assertFalse(capture.isBinary());
    }

    @Test
    @DisplayName("收尾时把未完成的残余字节冲出来")
    void close_should_flushPendingDecoderState() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);

        // 只写前两个字节，第三个字节永远不来：替换字符正是「这里有个坏序列」的如实表达
        byte[] bytes = "中".getBytes(StandardCharsets.UTF_8);
        capture.write(bytes, 0, 2);
        capture.close();

        assertEquals("\uFFFD", sink.body());
    }

    @Test
    @DisplayName("出现 NUL 即判定为二进制，且不再写入正文")
    void write_should_markBinary_whenNulPresent() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);

        capture.write("文本".getBytes(StandardCharsets.UTF_8), 0, "文本".getBytes(StandardCharsets.UTF_8).length);
        capture.write(new byte[] {1, 2, 0, 3, 4}, 0, 5);
        capture.write("后续文本".getBytes(StandardCharsets.UTF_8), 0, 4);
        capture.close();

        assertTrue(capture.isBinary());
        assertEquals("文本", sink.body());
        assertEquals("文本".getBytes(StandardCharsets.UTF_8).length + 5 + 4, capture.bytes());
    }

    @Test
    @DisplayName("大量替换字符判定为二进制")
    void write_should_markBinary_whenTooManyReplacements() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);

        byte[] garbage = new byte[64];
        for (int index = 0; index < garbage.length; index++) {
            garbage[index] = (byte) 0xFF;
        }
        capture.write(garbage, 0, garbage.length);
        capture.close();

        assertTrue(capture.isBinary());
        assertEquals("", sink.body());
    }

    @Test
    @DisplayName("少量坏字节不判定为二进制：开头恰好有个坏字节不该让整条命令的输出消失")
    void write_should_notMarkBinary_forFewBadBytes() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);

        byte[] text = "正常的一大段输出内容，足够长到让占比判据不会误判。".getBytes(StandardCharsets.UTF_8);
        capture.write(text, 0, text.length);
        capture.write(new byte[] {(byte) 0xFF}, 0, 1);
        capture.close();

        assertFalse(capture.isBinary());
        assertTrue(sink.body().contains("正常的一大段输出内容"));
    }

    @Test
    @DisplayName("收尾后的写入被忽略——泵线程可能比收尾晚一拍")
    void write_should_ignoreAfterClose() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);
        capture.close();

        capture.write("迟到".getBytes(StandardCharsets.UTF_8), 0, 6);

        assertEquals("", sink.body());
    }

    @Test
    @DisplayName("二进制时仍然计数：停止读取会让子进程因管道写满而卡住")
    void write_should_keepCounting_whenBinary() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);
        byte[] binary = new byte[1024];

        capture.write(binary, 0, binary.length);

        assertTrue(capture.isBinary());
        assertEquals(1024L, capture.bytes());
    }

    @Test
    @DisplayName("收到输出会刷新最近输出时刻，供静默超时判定")
    void write_should_refreshLastOutputAt() throws InterruptedException {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellOutputCapture capture = new ShellOutputCapture(sink);
        long before = capture.lastOutputAt();

        Thread.sleep(20L);
        capture.write("x".getBytes(StandardCharsets.UTF_8), 0, 1);

        assertTrue(capture.lastOutputAt() > before);
    }

    /**
     * 写入一段文本。
     *
     * @param capture 捕获流
     * @param text    文本
     */
    private static void write(ShellOutputCapture capture, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        capture.write(bytes, 0, bytes.length);
    }
}
