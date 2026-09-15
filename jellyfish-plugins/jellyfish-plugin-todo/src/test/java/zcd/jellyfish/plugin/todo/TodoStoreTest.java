package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoStore} 的单元测试：验证懒加载、整表覆盖、空表删文件与安全护栏。
 *
 * @author zcd
 */
@DisplayName("待办仓库")
class TodoStoreTest {

    /** 每个用例一个独立目录，避免相互污染。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
    }

    @Test
    @DisplayName("从没写过待办时返回空列表，不创建任何文件")
    void itemsOf_should_returnEmpty_when_fileAbsent() {
        assertTrue(store.itemsOf("s-1").isEmpty());
        assertFalse(Files.exists(store.fileOf("s-1")));
    }

    @Test
    @DisplayName("覆盖后应能读回，并把文件写到磁盘")
    void replace_should_writeFileAndBeReadable() {
        List<TodoItem> stored = store.replace("s-1", Arrays.asList(new TodoItem("写文档", true)));

        assertEquals(1, stored.size());
        assertTrue(Files.exists(store.fileOf("s-1")));
        assertEquals(1, store.itemsOf("s-1").size());
        assertTrue(store.itemsOf("s-1").get(0).done());
    }

    @Test
    @DisplayName("另一个仓库实例应能从文件懒加载：这是重启后待办还在的依据")
    void itemsOf_should_loadFromFile_when_newStoreInstance() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", false)));

        TodoStore reopened = new TodoStore(directory);

        assertEquals(1, reopened.itemsOf("s-1").size());
        assertEquals("写文档", reopened.itemsOf("s-1").get(0).content());
    }

    @Test
    @DisplayName("覆盖为空表应删除文件，不留空壳")
    void replace_should_deleteFile_when_empty() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", false)));

        List<TodoItem> stored = store.replace("s-1", Collections.<TodoItem>emptyList());

        assertTrue(stored.isEmpty());
        assertFalse(Files.exists(store.fileOf("s-1")));
        assertTrue(store.itemsOf("s-1").isEmpty());
    }

    @Test
    @DisplayName("会话之间互不影响")
    void itemsOf_should_isolatePerSession() {
        store.replace("s-1", Arrays.asList(new TodoItem("a", false)));
        store.replace("s-2", Arrays.asList(new TodoItem("b", false), new TodoItem("c", true)));

        assertEquals(1, store.itemsOf("s-1").size());
        assertEquals(2, store.itemsOf("s-2").size());
    }

    @Test
    @DisplayName("会话标识会拼进文件路径，含分隔符或上跳片段必须被拒绝")
    void itemsOf_should_rejectUnsafeSessionId() {
        assertThrows(JellyfishException.class, () -> store.itemsOf("../escape"));
        assertThrows(JellyfishException.class, () -> store.itemsOf("a/b"));
        assertThrows(JellyfishException.class, () -> store.itemsOf("   "));
        assertThrows(JellyfishException.class, () -> store.itemsOf(null));
    }

    @Test
    @DisplayName("坏文件必须抛错而不是当作空：否则下一次写入就把原数据覆盖掉了")
    void itemsOf_should_fail_when_fileCorrupt() throws IOException {
        Files.write(store.fileOf("s-1"), "{不是数组".getBytes(StandardCharsets.UTF_8));

        assertThrows(JellyfishException.class, () -> store.itemsOf("s-1"));
    }
}
