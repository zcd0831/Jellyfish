package zcd.jellyfish.server.http;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.server.dto.TurnTextEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SseWriter} 的分帧契约。
 * <p>
 * 这里只测「字节长什么样」——SSE 的可用性完全由帧格式决定，而帧格式是一段文本，
 * 用真实的 {@link ByteArrayOutputStream} 比用 mock 断言调用更有意义。
 *
 * @author zcd
 */
class SseWriterTest {

    /**
     * 取写出器输出文本。
     *
     * @param out 输出流
     * @return UTF-8 文本
     */
    private static String text(ByteArrayOutputStream out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void event_should_write_event_and_data_lines_when_payload_given() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SseWriter writer = new SseWriter(out);

        writer.event("text", new TurnTextEvent("t1", "hi"));

        assertEquals("event: text\ndata: {\"turnId\":\"t1\",\"delta\":\"hi\"}\n\n", text(out));
    }

    @Test
    void event_should_escape_newline_inside_data_when_text_contains_break() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SseWriter writer = new SseWriter(out);

        writer.event("text", new TurnTextEvent("t1", "a\nb"));

        String frame = text(out);
        // JSON 转义后 data 行内不会出现裸换行，否则一条事件会被拆成两条 data 行
        assertEquals("event: text\ndata: {\"turnId\":\"t1\",\"delta\":\"a\\nb\"}\n\n", frame);
    }

    @Test
    void comment_should_write_comment_frame_when_called() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SseWriter writer = new SseWriter(out);

        writer.comment("keepalive");

        assertEquals(": keepalive\n\n", text(out));
    }

    @Test
    void event_should_propagate_io_failure_when_output_closed() throws IOException {
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        };
        SseWriter writer = new SseWriter(failing);

        assertThrows(IOException.class, () -> writer.event("text", new TurnTextEvent("t1", "x")));
    }
}
