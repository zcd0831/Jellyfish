package zcd.jellyfish.plugin.sessionfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.SessionSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionStore} 的单元测试。
 * <p>
 * 重点锁住三件事：一个会话一个文件、内容未变不重写、会话标识不能把文件写到目录之外。
 *
 * @author zcd
 */
@DisplayName("会话文件仓库")
class SessionStoreTest {

    /** 用例独立的工作目录。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("一个会话对应一个同名 JSON 文件")
    void fileOf_should_useSessionIdAsFileName() {
        Path file = new SessionStore(tempDir).fileOf("session-1");

        assertEquals(tempDir.resolve("session-1.json"), file);
    }

    @Test
    @DisplayName("首次落盘应真正写入，并自动创建目录")
    void writeIfChanged_should_writeAndCreateDirectory_when_firstTime() throws IOException {
        SessionStore store = new SessionStore(tempDir.resolve("nested"));

        assertTrue(store.writeIfChanged("session-1", "{\"a\":1}"));

        assertEquals("{\"a\":1}", new String(
                Files.readAllBytes(store.fileOf("session-1")), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("内容未变时不应重写文件")
    void writeIfChanged_should_skip_when_contentUnchanged() throws IOException {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", "{\"a\":1}");
        Path file = store.fileOf("session-1");
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(file);

        assertFalse(store.writeIfChanged("session-1", "{\"a\":1}"));

        assertEquals(before, Files.getLastModifiedTime(file));
    }

    @Test
    @DisplayName("内容变化时应覆盖写入")
    void writeIfChanged_should_overwrite_when_contentChanged() throws IOException {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", "{\"a\":1}");

        assertTrue(store.writeIfChanged("session-1", "{\"a\":2}"));

        assertEquals("{\"a\":2}", new String(
                Files.readAllBytes(store.fileOf("session-1")), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("原子替换不应留下临时文件")
    void writeIfChanged_should_notLeaveTempFiles() throws IOException {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", "{\"a\":1}");
        store.writeIfChanged("session-1", "{\"a\":2}");

        try (java.util.stream.Stream<Path> paths = Files.list(tempDir)) {
            assertEquals(1, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    @DisplayName("列举只认 .json，临时文件与其它文件都不算会话")
    void files_should_onlyReturnSessionFiles() throws IOException {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", "{}");
        store.writeIfChanged("session-2", "{}");
        Files.write(tempDir.resolve("session-3.json.tmp"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("notes.txt"), "x".getBytes(StandardCharsets.UTF_8));

        List<Path> files = store.files();

        assertEquals(2, files.size());
        assertEquals("session-1.json", files.get(0).getFileName().toString());
        assertEquals("session-2.json", files.get(1).getFileName().toString());
    }

    @Test
    @DisplayName("目录不存在时列举为空而不是报错")
    void files_should_returnEmpty_when_directoryMissing() {
        assertTrue(new SessionStore(tempDir.resolve("missing")).files().isEmpty());
    }

    @Test
    @DisplayName("会话标识含路径分隔符或上跳片段时必须拒绝：它可能来自外部文件")
    void fileOf_should_rejectUnsafeSessionId() {
        SessionStore store = new SessionStore(tempDir);

        assertThrows(JellyfishException.class, () -> store.fileOf("../../etc/passwd"));
        assertThrows(JellyfishException.class, () -> store.fileOf("a/b"));
        assertThrows(JellyfishException.class, () -> store.fileOf("a\\b"));
        assertThrows(JellyfishException.class, () -> store.fileOf("  "));
    }

    @Test
    @DisplayName("读取应原样返回文件内容")
    void read_should_returnContent() {
        SessionStore store = new SessionStore(tempDir);
        SessionSnapshot snapshot = TestSnapshots.minimal("session-1");
        store.writeIfChanged("session-1", SnapshotJson.write(snapshot));

        assertEquals(SnapshotJson.write(snapshot), store.read(store.fileOf("session-1")));
    }

    @Test
    @DisplayName("删除应移除文件；文件不存在时返回 false 而不是抛错")
    void delete_should_removeFile_and_beIdempotent() {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", SnapshotJson.write(TestSnapshots.minimal("session-1")));

        assertTrue(store.delete("session-1"));
        assertFalse(Files.exists(store.fileOf("session-1")));
        assertFalse(store.delete("session-1"));
    }
}
