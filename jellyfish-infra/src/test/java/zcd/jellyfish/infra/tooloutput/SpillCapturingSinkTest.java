package zcd.jellyfish.infra.tooloutput;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.ToolOutputSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link SpillCapturingSink} 的单元测试。
 * <p>
 * 要守住的性质有四条：<b>短输出不碰磁盘</b>（不能因为引入了捕获通道就让每次调用都产生文件）、
 * <b>溢出后磁盘上必须是完整内容</b>（否则「内容不丢」就成了空话）、
 * <b>内存只与预览预算相关</b>（喂进百万级字符后预览仍然是有界的）、
 * 以及<b>元数据行在两条路径上都可见</b>（信封里也看得到退出码与工作目录）。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpillCapturingSink 捕获期输出")
class SpillCapturingSinkTest {

    /** 每个用例独立的落盘根目录。 */
    @TempDir
    Path tempDir;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 真实的落盘存储：本类要验证的正是「内容真的落到了磁盘上」。 */
    private ToolOutputStore store;

    @BeforeEach
    void setUp() {
        store = new ToolOutputStore(runtimeConfig);
    }

    @Test
    @DisplayName("短输出应原样返回且不产生任何文件")
    void finish_should_returnPlainText_withoutSpilling() throws IOException {
        // Given：未溢出的路径不读配置、不碰磁盘
        ToolOutputSink sink = sink(300, 0);

        // When
        sink.summary("cwd: /tmp · exit: 0");
        sink.write("hello\n");
        sink.write("world\n");
        String text = sink.finish();

        // Then
        assertEquals("cwd: /tmp · exit: 0\nhello\nworld\n", text);
        assertTrue(listFiles().isEmpty(), "短输出不该落盘");
    }

    @Test
    @DisplayName("溢出时应落盘完整内容，并在信封里给出路径")
    void finish_should_spillCompleteContent_whenOverBudget() throws IOException {
        // Given
        configure(2000, 0);
        ToolOutputSink sink = sink(2000, 0);
        sink.summary("cwd: /tmp · exit: 0");
        StringBuilder expected = new StringBuilder();

        // When：写约 3800 字符，超过预算
        for (int i = 0; i < 400; i++) {
            String chunk = "chunk-" + i + "\n";
            expected.append(chunk);
            sink.write(chunk);
        }
        String text = sink.finish();

        // Then
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(text);
        assertNotNull(envelope, text);
        assertNotNull(envelope.getPath(), text);
        assertFalse(envelope.isPartial(), text);
        assertEquals(expected.length() + "cwd: /tmp · exit: 0".length(), envelope.getTotalChars());
        assertEquals(expected.toString(), read(envelope.getPath()));
        assertTrue(text.length() <= 2000, "回灌文本长度 " + text.length());
    }

    @Test
    @DisplayName("溢出后的预览应包含元数据首行、开头与结尾")
    void finish_should_keepSummaryAndBothEnds_inPreview() {
        // Given
        configure(2000, 0);
        ToolOutputSink sink = sink(2000, 0);
        sink.summary("cwd: /repo · exit: 1");
        for (int i = 0; i < 300; i++) {
            sink.write("line-" + i + "\n");
        }

        // When
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(sink.finish());

        // Then
        assertNotNull(envelope);
        String preview = envelope.getPreview().asText();
        assertTrue(preview.startsWith("cwd: /repo · exit: 1"), preview);
        assertTrue(preview.contains("line-0\n"), preview);
        assertTrue(preview.endsWith("line-299\n"), preview);
        assertTrue(preview.contains("省略"), preview);
    }

    @Test
    @DisplayName("finish 幂等：重复调用返回同一份文本")
    void finish_should_beIdempotent() {
        // Given
        configure(120, 0);
        ToolOutputSink sink = sink(120, 0);
        sink.summary("exit: 0");
        for (int i = 0; i < 100; i++) {
            sink.write("0123456789\n");
        }

        // When
        String first = sink.finish();
        String second = sink.finish();
        String third = sink.finish();

        // Then：内核会在 finally 里兜底再调一次，重复调用绝不能追加或报错
        assertEquals(first, second);
        assertEquals(first, third);
    }

    @Test
    @DisplayName("从未捕获到内容时返回 null")
    void finish_should_returnNull_whenNothingCaptured() {
        // Then：调用点据此判断「本次没有走捕获路径」
        assertNull(sink(120, 0).finish());
        assertNull(sink(120, 0).finish());
    }

    @Test
    @DisplayName("内存中的预览只与预算相关，与输出体积无关")
    void finish_should_keepPreviewBounded_forHugeOutput() {
        // Given：喂进约 2 MiB 字符
        int maxChars = 500;
        configure(maxChars, 0);
        ToolOutputSink sink = sink(maxChars, 0);
        String chunk = repeat('x', 1000);

        // When
        for (int i = 0; i < 2000; i++) {
            sink.write(chunk);
        }
        String text = sink.finish();

        // Then：总量如实记录，但回灌文本仍然被预算封住
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(text);
        assertNotNull(envelope, "信封长度 " + text.length());
        assertEquals(2000 * 1000, envelope.getTotalChars());
        assertTrue(text.length() <= maxChars, "回灌文本长度 " + text.length());
        assertTrue(envelope.getPreview().asText().length() <= maxChars);
    }

    @Test
    @DisplayName("超过落盘上限时应部分落盘并如实标注")
    void finish_should_markPartial_whenSpillCapReached() throws IOException {
        // Given：上限小到第一段缓冲就装不下
        configure(300, 64);
        ToolOutputSink sink = sink(300, 64);

        // When
        for (int i = 0; i < 100; i++) {
            sink.write("0123456789\n");
        }
        String text = sink.finish();

        // Then：文件里确实有前一段内容（不是空文件），但承诺必须降级成「未全部保存」
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(text);
        assertNotNull(envelope, text);
        assertNotNull(envelope.getPath(), text);
        assertTrue(envelope.isPartial(), text);
        assertTrue(envelope.render().contains("未捕获"), text);
        assertTrue(envelope.stub().contains("不完整"), envelope.stub());
        String saved = read(envelope.getPath());
        assertFalse(saved.isEmpty(), "上限大于零时不该写出空文件");
        assertTrue(saved.length() <= 64, "落盘长度 " + saved.length());
    }

    @Test
    @DisplayName("落盘不可用时信封需如实说不可恢复，且不影响回灌")
    void finish_should_reportUnrecoverable_whenStoreFails() throws IOException {
        // Given：把落盘根目录指向一个普通文件，创建目录必然失败
        Path blocker = tempDir.resolve("blocker");
        Files.write(blocker, Collections.singletonList("x"), StandardCharsets.UTF_8);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(null, null, 300, null, null, null,
                new ToolOutputSettings(blocker.toString(), 0, 0L, 0L, null)));
        ToolOutputSink sink = sink(300, 0);

        // When
        for (int i = 0; i < 200; i++) {
            sink.write("0123456789\n");
        }
        String text = sink.finish();

        // Then：磁盘不可写不该把一次工具调用升级成故障，信封如实降级
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(text);
        assertNotNull(envelope, text);
        assertNull(envelope.getPath(), text);
        assertTrue(envelope.stub().contains("不可恢复"), envelope.stub());
    }

    @Test
    @DisplayName("并发写入不得丢失内容")
    void write_should_beThreadSafe() throws InterruptedException, IOException {
        // Given
        configure(200, 0);
        ToolOutputSink sink = sink(200, 0);
        int threads = 4;
        int perThread = 50;
        String chunk = repeat('y', 100);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    sink.write(chunk);
                }
            }, "writer-" + t);
            workers.add(worker);
            worker.start();
        }

        // When
        start.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        }
        ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(sink.finish());

        // Then：stdout 与 stderr 由两条泵线程各自读取，计数与落盘都不能少
        assertNotNull(envelope);
        assertEquals(threads * perThread * chunk.length(), envelope.getTotalChars());
        assertEquals(threads * perThread * chunk.length(), read(envelope.getPath()).length());
    }

    @Test
    @DisplayName("实时输出旁路应收到全部片段")
    void write_should_teeEveryChunk() {
        // Given
        List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        ToolOutputSink sink = new SpillCapturingSink(store, "s-1", "c-1", "shell", 120, 0, seen::add);

        // When
        sink.write("a");
        sink.write("b");
        sink.write("c");
        sink.finish();

        // Then
        assertEquals(3, seen.size());
        assertEquals("abc", String.join("", seen));
    }

    @Test
    @DisplayName("收尾之后的写入应被丢弃而不是抛异常")
    void write_should_beIgnored_afterFinish() {
        // Given
        ToolOutputSink sink = sink(120, 0);
        sink.write("before");
        String finished = sink.finish();

        // When：迟到的片段（例如泵线程收尾的最后一拍）
        sink.write("late");

        // Then
        assertEquals(finished, sink.finish());
    }

    /**
     * 把本次生效的配置写进桩。
     *
     * @param maxChars      回灌字符预算
     * @param spillMaxBytes 落盘字节上限，{@code 0} 表示不限制
     */
    private void configure(int maxChars, long spillMaxBytes) {
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(null, null, maxChars, null, null, null,
                new ToolOutputSettings(tempDir.toString(), 0, 0L, spillMaxBytes, null)));
    }

    /**
     * 造一个被测 sink。
     *
     * @param maxChars      回灌字符预算
     * @param spillMaxBytes 落盘字节上限
     * @return sink
     */
    private ToolOutputSink sink(int maxChars, long spillMaxBytes) {
        return new SpillCapturingSink(store, "s-1", "c-1", "shell", maxChars, spillMaxBytes, null);
    }

    /**
     * 读回落盘文件内容。
     *
     * @param path 文件路径
     * @return 文本内容
     * @throws IOException 读取失败时抛出
     */
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /**
     * 列举落盘目录下的全部文件。
     *
     * @return 文件列表
     * @throws IOException 列举失败时抛出
     */
    private List<Path> listFiles() throws IOException {
        List<Path> files = new ArrayList<Path>();
        if (!Files.exists(tempDir)) {
            return files;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(tempDir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        return files;
    }

    /**
     * 生成重复文本。
     *
     * @param c     字符
     * @param times 次数
     * @return 文本
     */
    private static String repeat(char c, int times) {
        StringBuilder builder = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
