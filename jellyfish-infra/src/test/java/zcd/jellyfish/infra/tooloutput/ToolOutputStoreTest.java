package zcd.jellyfish.infra.tooloutput;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.ToolOutputSettings;
import zcd.jellyfish.infra.support.HomePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link ToolOutputStore} 的单元测试：锁住写入内容、文件名清洗与「清理不删最新」三条。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolOutputStore 工具结果落盘")
class ToolOutputStoreTest {

    /** 每个用例独立的落盘根目录。 */
    @TempDir
    Path tempDir;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 被测存储。 */
    private ToolOutputStore store;

    @BeforeEach
    void setUp() {
        store = new ToolOutputStore(runtimeConfig);
    }

    @Test
    @DisplayName("落盘应写出内容并返回可读回的绝对路径")
    void store_should_writeFile_andReturnPath() throws IOException {
        // Given
        settings(0, 0);

        // When
        String path = store.store("s-1", "call-1", "read_file", "完整内容", false);

        // Then
        assertNotNull(path);
        assertTrue(Paths.get(path).isAbsolute(), path);
        assertEquals("完整内容", new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
        assertTrue(path.contains("read_file"), path);
        assertTrue(path.endsWith(".txt"), path);
    }

    @Test
    @DisplayName("同一个调用 id 的两次落盘不得互相覆盖")
    void store_should_not_overwrite_when_callIdRepeats() throws IOException {
        settings(0, 0);

        // When：id 由外部给（模型 / 适配器），内核不保证它跨回合唯一；清洗还会把不同的 id 折叠成
        // 同一个名字。文件名里那一段唯一片段就是为这一步准备的
        String first = store.store("s-1", "call-1", "read_file", "第一次的内容", false);
        String second = store.store("s-1", "call-1", "read_file", "第二次的内容", false);

        // Then：两条信封里的 _path 必须各自指向自己的内容——「路径存在但内容不是它」比文件不存在更难发现
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertEquals("第一次的内容", new String(Files.readAllBytes(Paths.get(first)), StandardCharsets.UTF_8));
        assertEquals("第二次的内容", new String(Files.readAllBytes(Paths.get(second)), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("结构化结果的扩展名应为 .json")
    void store_should_useJsonSuffix_when_structured() {
        settings(0, 0);

        String path = store.store("s-1", "call-1", "jira", "[1]", true);

        assertNotNull(path);
        assertTrue(path.endsWith(".json"), path);
    }

    @Test
    @DisplayName("会话标识里的路径分隔符必须被清洗，不能写到目录外")
    void store_should_sanitizeSessionId() {
        settings(0, 0);

        String path = store.store("../../escape", "call", "tool", "x", false);

        assertNotNull(path);
        assertTrue(Paths.get(path).startsWith(tempDir), path);
    }

    @Test
    @DisplayName("目录不可写时应返回 null，而不是把回合打断")
    void store_should_returnNull_when_directoryNotWritable() throws IOException {
        // Given：把落盘根指向一个普通文件
        Path blocker = tempDir.resolve("blocker");
        Files.write(blocker, "x".getBytes(StandardCharsets.UTF_8));
        when(runtimeConfig.getReactSettings()).thenReturn(
                new ReactSettings(null, null, null, null, null, null,
                        new ToolOutputSettings(blocker.toString(), 0, 0L, null, null)));

        // When / Then
        assertNull(store.store("s", "c", "t", "content", false));
    }

    @Test
    @DisplayName("超过文件数上限时应从最旧开始清理，且不删最新")
    void store_should_cleanupOldest_when_overKeepFiles() throws IOException {
        // Given：每会话最多保留 2 个
        settings(2, 0);
        String oldest = store.store("s", "a", "t", "1", false);
        setLastModified(oldest, 1000L);
        String middle = store.store("s", "b", "t", "2", false);
        setLastModified(middle, 2000L);

        // When：第三次写入触发清理
        String newest = store.store("s", "c", "t", "3", false);

        // Then
        List<Path> remaining = listSessionFiles("s");
        assertEquals(2, remaining.size());
        assertTrue(Files.exists(Paths.get(middle)));
        assertTrue(Files.exists(Paths.get(newest)));
        assertTrue(Files.notExists(Paths.get(oldest)));
    }

    @Test
    @DisplayName("命名空间落盘应写到根目录下的独立子目录")
    void storeIn_should_writeIntoItsOwnNamespaceDirectory() {
        // Given
        settings(0, 0);

        // When
        String path = store.storeIn("subagent-runs", "run-1", "{}", true, 0, 0);

        // Then：键是 runId，目录是命名空间——它与会话目录不是同一个地方
        assertNotNull(path);
        assertTrue(path.endsWith("run-1.json"), path);
        assertEquals(tempDir.resolve("subagent-runs"), Paths.get(path).getParent());
    }

    @Test
    @DisplayName("命名空间的配额独立：只清自己目录，不碰会话目录")
    void storeIn_should_applyItsOwnQuotaWithoutTouchingSessionFiles() throws IOException {
        // Given：会话目录配额宽松，归档目录只留一个
        settings(200, 0);
        String oldest = store.storeIn("subagent-runs", "run-1", "a", true, 1, 0);
        setLastModified(oldest, 1000L);
        String toolOutput = store.store("s", "call", "read_file", "1", false);

        // When：第二个归档触发清理
        String newest = store.storeIn("subagent-runs", "run-2", "b", true, 1, 0);

        // Then：最旧的归档被清掉，而工具输出完全不受归档配额影响
        assertTrue(Files.notExists(Paths.get(oldest)));
        assertTrue(Files.exists(Paths.get(newest)));
        assertTrue(Files.exists(Paths.get(toolOutput)));
    }

    @Test
    @DisplayName("落盘根在主目录内时，回灌的三条路径都应缩写成 ~ 形式")
    void allPaths_should_abbreviateRealHome_when_directoryIsUnderHome() throws IOException {
        // Given：user.home 指向临时目录，落盘根写成 ~/tool-outputs（真实部署的缺省形态）
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            when(runtimeConfig.getReactSettings()).thenReturn(
                    new ReactSettings(null, null, null, null, null, null,
                            new ToolOutputSettings("~/tool-outputs", 0, 0L, null, null)));
            ToolOutputStore.SpillWriter writer = store.open("s-1", "call-1", "shell");

            // When：事后截断、捕获期溢出、子代理归档三条返回路径各取一个
            String spilled = store.store("s-1", "call-1", "read_file", "x", false);
            String archived = store.storeIn("subagent-runs", "run-1", "{}", true, 0, 0);
            writer.write("y");
            String committed = writer.commit();

            // Then：真实用户名与主目录结构不进上下文（它会随会话落盘、被导出、发给上游模型），
            // 而回灌的路径展开后仍指向那个真实文件——两条都成立才叫「缩写了但能用」
            for (String path : new String[]{spilled, archived, committed}) {
                assertNotNull(path);
                assertTrue(path.startsWith("~"), path);
                assertFalse(path.contains(tempDir.toString()), path);
            }
            assertEquals("x", new String(Files.readAllBytes(Paths.get(HomePaths.expand(spilled))),
                    StandardCharsets.UTF_8));
            assertEquals("y", new String(Files.readAllBytes(Paths.get(HomePaths.expand(committed))),
                    StandardCharsets.UTF_8));
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    @DisplayName("清洗规则应替换非法字符并抹掉 ..")
    void sanitize_should_replaceIllegalCharacters() {
        assertEquals("a_b_c", ToolOutputStore.sanitize("a/b\\c"));
        assertEquals("unnamed", ToolOutputStore.sanitize("   "));
        assertTrue(ToolOutputStore.sanitize("...").indexOf("..") < 0);
    }

    /**
     * 把落盘与清理参数写进配置桩。
     *
     * @param keepFiles 文件数上限，{@code 0} 表示不清理
     * @param maxBytes  字节上限，{@code 0} 表示不清理
     */
    private void settings(int keepFiles, long maxBytes) {
        when(runtimeConfig.getReactSettings()).thenReturn(
                new ReactSettings(null, null, null, null, null, null,
                        new ToolOutputSettings(tempDir.toString(), keepFiles, maxBytes, null, null)));
    }

    @Test
    @DisplayName("增量写入应在收尾时原子改名，且不留临时文件")
    void open_should_writeIncrementally_andCommitAtomically() throws IOException {
        // Given
        settings(0, 0);
        ToolOutputStore.SpillWriter writer = store.open("s-1", "call-1", "shell");

        // When
        writer.write("a");
        writer.write("b");
        writer.write("c");
        String path = writer.commit();

        // Then：读到半成品比读不到更糟，因此收尾之前它对外不存在
        assertNotNull(path);
        assertEquals("abc", new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
        assertFalse(containsPartFile(tempDir.resolve("s-1")));
    }

    @Test
    @DisplayName("增量写入的收尾应幂等")
    void commit_should_beIdempotent() {
        // Given
        settings(0, 0);
        ToolOutputStore.SpillWriter writer = store.open("s-1", "call-1", "shell");
        writer.write("x");

        // When
        String first = writer.commit();
        String second = writer.commit();

        // Then
        assertNotNull(first);
        assertEquals(first, second);
    }

    @Test
    @DisplayName("放弃时应删掉临时文件且不返回路径")
    void abort_should_deleteTempFile() throws IOException {
        // Given
        settings(0, 0);
        ToolOutputStore.SpillWriter writer = store.open("s-1", "call-1", "shell");
        writer.write("half-written");

        // When
        writer.abort();

        // Then
        assertNull(writer.commit());
        assertFalse(containsPartFile(tempDir.resolve("s-1")));
    }

    @Test
    @DisplayName("目录不可用时返回失效写入器而不报错")
    void open_should_returnFailedWriter_whenDirectoryUnavailable() throws IOException {
        // Given：把落盘根目录指向一个普通文件
        Path blocker = tempDir.resolve("blocker");
        Files.write(blocker, java.util.Collections.singletonList("x"), StandardCharsets.UTF_8);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(null, null, null, null, null, null,
                new ToolOutputSettings(blocker.toString(), 0, 0L, 0L, null)));

        // When：磁盘不可写不该把一次工具调用升级成故障
        ToolOutputStore.SpillWriter writer = store.open("s-1", "call-1", "shell");
        writer.write("content");

        // Then
        assertEquals(0L, writer.getBytesWritten());
        assertNull(writer.commit());
    }

    /**
     * 判断目录下是否还留着临时文件。
     *
     * @param directory 目录
     * @return 存在返回 {@code true}
     * @throws IOException 列举失败时抛出
     */
    private static boolean containsPartFile(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return false;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".part"));
        }
    }

    /**
     * 设置文件修改时间，让清理顺序可确定。
     *
     * @param path          文件路径
     * @param millis        毫秒时间戳
     * @throws IOException 设置失败时抛出
     */
    private static void setLastModified(String path, long millis) throws IOException {
        Files.setLastModifiedTime(Paths.get(path), FileTime.fromMillis(millis));
    }

    /**
     * 列举某会话目录下的文件。
     *
     * @param sessionId 会话标识
     * @return 文件列表
     * @throws IOException 列举失败时抛出
     */
    private List<Path> listSessionFiles(String sessionId) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(tempDir.resolve(sessionId))) {
            stream.forEach(files::add);
        }
        return files;
    }
}
