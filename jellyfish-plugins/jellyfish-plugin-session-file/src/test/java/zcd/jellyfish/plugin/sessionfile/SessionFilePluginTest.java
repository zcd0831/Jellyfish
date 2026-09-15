package zcd.jellyfish.plugin.sessionfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.SessionDeleteRequest;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionFilePlugin} 的行为测试：走真实上下文（注册表 + 事件通道）而不是 mock。
 * <p>
 * 用真实 {@link PluginContext} 的理由：本插件的全部行为都发生在「handler 被内核调用」那一刻，
 * 把上下文换成 mock 就只能验证「注册了几次」，验证不了「注册之后到底能不能用」。
 *
 * @author zcd
 */
@DisplayName("会话文件插件行为")
class SessionFilePluginTest {

    /** 本插件标识。 */
    private static final String PLUGIN_ID = "jellyfish-session-file";

    /** 用例独立的工作目录。 */
    @TempDir
    Path tempDir;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略，持久化 handler 的落点。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel eventChannel;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        eventChannel = new EventChannel(EventChannelOptions.defaults(), registry);
        eventChannel.start();
    }

    @AfterEach
    void tearDown() {
        eventChannel.close();
    }

    @Test
    @DisplayName("落盘应写出一个 JSON 文件，内容能原样读回")
    void persist_should_writeJsonFile() throws IOException {
        startPlugin(false);
        SessionSnapshot snapshot = TestSnapshots.full("session-1");

        persist(snapshot);

        Path file = tempDir.resolve("session-1.json");
        assertTrue(Files.exists(file), "会话文件应已写出");
        SessionSnapshot restored = readBack(file);
        assertEquals(snapshot.getSessionId(), restored.getSessionId());
        assertEquals(snapshot.getMessages().size(), restored.getMessages().size());
    }

    @Test
    @DisplayName("内容未变时不应重写文件")
    void persist_should_notRewriteUnchangedContent() throws IOException {
        startPlugin(false);
        SessionSnapshot snapshot = TestSnapshots.full("session-1");
        persist(snapshot);
        Path file = tempDir.resolve("session-1.json");
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(file);

        persist(snapshot);

        assertEquals(before, Files.getLastModifiedTime(file));
    }

    @Test
    @DisplayName("恢复应把目录里的会话都交回去")
    void restore_should_returnAllSessions() {
        startPlugin(false);
        persist(TestSnapshots.full("session-1"));
        persist(TestSnapshots.full("session-2"));

        SessionRestoreResult result = restore();

        assertEquals(2, result.getSessions().size());
    }

    @Test
    @DisplayName("目录不存在时恢复应为空，而不是报错")
    void restore_should_returnEmpty_when_nothingPersisted() {
        startPlugin(false);

        assertEquals(0, restore().getSessions().size());
    }

    @Test
    @DisplayName("单个坏文件应被跳过，不能拖累同目录其它会话")
    void restore_should_skipBrokenFile() throws IOException {
        startPlugin(false);
        persist(TestSnapshots.full("session-1"));
        Files.write(tempDir.resolve("session-2.json"), "{半截".getBytes(StandardCharsets.UTF_8));

        SessionRestoreResult result = restore();

        assertEquals(1, result.getSessions().size());
        assertEquals("session-1", result.getSessions().get(0).getSessionId());
    }

    @Test
    @DisplayName("启用 git 时应建立仓库并留下提交")
    void persist_should_commit_when_gitEnabled() {
        startPlugin(true);

        persist(TestSnapshots.full("session-1"));

        assertTrue(Files.isDirectory(tempDir.resolve(".git")));
        assertTrue(Files.isDirectory(tempDir.resolve(".git/refs")));
    }

    @Test
    @DisplayName("关闭 git 时不应建立仓库：文件仍照常落盘")
    void persist_should_notInitRepository_when_gitDisabled() {
        startPlugin(false);

        persist(TestSnapshots.full("session-1"));

        assertFalse(Files.exists(tempDir.resolve(".git")));
        assertTrue(Files.exists(tempDir.resolve("session-1.json")));
    }

    @Test
    @DisplayName("删除请求应清掉会话文件，重启恢复不会再复活")
    void delete_should_removeFile() {
        startPlugin(false);
        persist(TestSnapshots.full("session-1"));

        delete("session-1");

        assertFalse(Files.exists(tempDir.resolve("session-1.json")));
        assertEquals(0, restore().getSessions().size());
    }

    @Test
    @DisplayName("删除不存在的会话文件是幂等的，不抛错")
    void delete_should_beIdempotentWhenMissing() {
        startPlugin(false);

        delete("session-1");
        delete("session-1");

        assertFalse(Files.exists(tempDir.resolve("session-1.json")));
    }

    /**
     * 启动插件，把配置指向临时目录。
     *
     * @param gitEnabled 是否启用 git
     */
    private void startPlugin(boolean gitEnabled) {
        Map<String, Object> configuration = new HashMap<String, Object>();
        configuration.put(PluginConfig.KEY_SESSION_DIR, tempDir.toString());
        configuration.put(PluginConfig.KEY_GIT_ENABLED, gitEnabled);
        PluginContext context = new PluginContextFactory(extensions, eventChannel, registry)
                .create(PluginDeclaration.of(PLUGIN_ID, configuration));
        new SessionFilePlugin().start(context);
    }

    /**
     * 通过注册表派发一次落盘请求。
     *
     * @param snapshot 会话快照
     */
    private void persist(SessionSnapshot snapshot) {
        extensions.invoke(extensions.handler(SessionPersistRequest.class, null),
                new SessionPersistRequest(snapshot));
    }

    /**
     * 通过注册表派发一次删除请求。
     *
     * @param sessionId 会话标识
     */
    private void delete(String sessionId) {
        extensions.invoke(extensions.handler(SessionDeleteRequest.class, null),
                new SessionDeleteRequest(sessionId));
    }

    /**
     * 通过注册表派发一次恢复请求。
     *
     * @return 恢复结果
     */
    private SessionRestoreResult restore() {
        return extensions.invoke(extensions.handler(SessionRestoreRequest.class, null),
                new SessionRestoreRequest());
    }

    /**
     * 直接从文件读回快照。
     *
     * @param file 文件路径
     * @return 会话快照
     * @throws IOException 读取失败时抛出
     */
    private static SessionSnapshot readBack(Path file) throws IOException {
        return SnapshotJson.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), file.toString());
    }
}
