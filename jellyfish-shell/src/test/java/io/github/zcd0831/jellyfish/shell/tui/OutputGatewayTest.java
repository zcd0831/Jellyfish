package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 输出单写者的两条路径：读取中走 `printAbove`、空闲时直写；状态行（只含 \r）读取期间直接丢弃。
 */
class OutputGatewayTest {

    private static void settle() throws InterruptedException {
        // 写者是独立线程：给它一点时间把队列排空
        Thread.sleep(120);
    }

    @Test
    void 空闲时直写目标流() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputGateway gateway = new OutputGateway(new PrintStream(bytes, true, "UTF-8"));

        gateway.sink().println("你好");
        settle();

        assertTrue(bytes.toString("UTF-8").contains("你好"));
        gateway.close();
    }

    @Test
    void 读取中走printAbove而不是直写() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputGateway gateway = new OutputGateway(new PrintStream(bytes, true, "UTF-8"));
        LineReader reader = mock(LineReader.class);
        when(reader.isReading()).thenReturn(true);
        List<String> above = Collections.synchronizedList(new ArrayList<String>());
        org.mockito.Mockito.doAnswer(invocation -> above.add(invocation.getArgument(0)))
                .when(reader).printAbove(org.mockito.ArgumentMatchers.anyString());

        gateway.reading(reader);
        gateway.sink().println("提问");
        settle();

        assertTrue(above.contains("提问"), "读取期间必须交给 printAbove：" + above);
        assertTrue(!bytes.toString("UTF-8").contains("提问"), "读取期间不得直写，否则会和提示符抢行");
        gateway.close();
    }

    @Test
    void 读取期间状态行被丢弃() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputGateway gateway = new OutputGateway(new PrintStream(bytes, true, "UTF-8"));
        LineReader reader = mock(LineReader.class);
        when(reader.isReading()).thenReturn(true);
        List<String> above = Collections.synchronizedList(new ArrayList<String>());
        org.mockito.Mockito.doAnswer(invocation -> above.add(invocation.getArgument(0)))
                .when(reader).printAbove(org.mockito.ArgumentMatchers.anyString());

        gateway.reading(reader);
        gateway.sink().print("\r\u001B[2K思考中…");
        settle();

        assertTrue(above.isEmpty(), "状态行是可丢的，读取期间直接丢弃：" + above);
        gateway.close();
    }
}
