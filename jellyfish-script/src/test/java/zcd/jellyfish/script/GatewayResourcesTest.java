package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关资源落盘的单元测试。
 * <p>
 * 这里最要紧的一条是「内容变了就换目录」：抽取目录若只按语言名分，改了网关代码却跑着旧文件
 * 这件事在开发期会被归因成「缓存没清」，代价是它可能一路带到生产。
 * 用内容摘要分目录，「当前跑的是哪一份」才能事后确认。
 *
 * @author zcd
 */
@DisplayName("网关资源落盘")
class GatewayResourcesTest {

    /** 抽取根目录。 */
    @TempDir
    Path baseDirectory;

    /** 测试资源名。 */
    private static final String GATEWAY = "script/gateway.py";

    @Test
    @DisplayName("应把资源写到以语言与内容摘要命名的目录下")
    void materialize_should_writeResourcesUnderLanguageAndDigestDirectory() throws Exception {
        GatewayResources resources = new GatewayResources(baseDirectory, classpathLoader());

        Path directory = resources.materialize(new FakeLanguage("python"),
                Collections.singletonList(GATEWAY));

        assertTrue(directory.startsWith(baseDirectory.resolve("python")), directory.toString());
        Path file = directory.resolve(GATEWAY);
        assertTrue(Files.isRegularFile(file));
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("网关"));
    }

    @Test
    @DisplayName("重复抽取应复用同一目录，且不重写文件")
    void materialize_should_reuseDirectory_when_contentIsUnchanged() throws Exception {
        GatewayResources resources = new GatewayResources(baseDirectory, classpathLoader());
        FakeLanguage language = new FakeLanguage("python");
        List<String> names = Collections.singletonList(GATEWAY);

        Path first = resources.materialize(language, names);
        FileTime stamp = Files.getLastModifiedTime(first.resolve(GATEWAY));
        Path second = resources.materialize(language, names);

        assertEquals(first, second);
        // 不重写是关键：文件没变过，是排查「改了代码为什么没生效」时唯一能确认的事实
        assertEquals(stamp, Files.getLastModifiedTime(first.resolve(GATEWAY)));
    }

    @Test
    @DisplayName("同一资源名内容变化应得到不同目录，避免复用旧文件")
    void materialize_should_useNewDirectory_when_contentChanges() {
        AtomicInteger version = new AtomicInteger();
        GatewayResources resources = new GatewayResources(baseDirectory,
                (language, name) -> ("网关第 " + version.incrementAndGet() + " 版").getBytes(StandardCharsets.UTF_8));
        FakeLanguage language = new FakeLanguage("python");
        List<String> names = Collections.singletonList(GATEWAY);

        Path first = resources.materialize(language, names);
        Path second = resources.materialize(language, names);

        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("资源清单变化也应得到不同目录")
    void materialize_should_useNewDirectory_when_resourceSetChanges() {
        GatewayResources resources = new GatewayResources(baseDirectory, classpathLoader());
        FakeLanguage language = new FakeLanguage("python");

        Path first = resources.materialize(language, Collections.singletonList(GATEWAY));
        Path second = resources.materialize(language, Arrays.asList(GATEWAY, "script/worker.py"));

        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("资源缺失应报错，且错误里带资源名")
    void materialize_should_fail_when_resourceIsMissing() {
        GatewayResources resources = new GatewayResources(baseDirectory, (language, name) -> {
            throw new JellyfishException("网关资源不存在: " + name);
        });

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> resources.materialize(new FakeLanguage("python"),
                        Collections.singletonList("script/missing.py")));

        assertTrue(failure.getMessage().contains("script/missing.py"), failure.getMessage());
    }

    @Test
    @DisplayName("默认来源下资源缺失应报错，而不是产出空文件")
    void materialize_should_fail_when_classpathResourceIsAbsent() {
        GatewayResources resources = new GatewayResources(baseDirectory);

        assertThrows(JellyfishException.class, () -> resources.materialize(new FakeLanguage("python"),
                Collections.singletonList("script/does-not-exist.py")));
    }

    @Test
    @DisplayName("空资源清单应报错，而不是静默产出空目录")
    void materialize_should_fail_when_noResourceIsDeclared() {
        GatewayResources resources = new GatewayResources(baseDirectory, classpathLoader());

        assertThrows(JellyfishException.class,
                () -> resources.materialize(new FakeLanguage("python"), Collections.emptyList()));
    }

    @Test
    @DisplayName("支持多文件与带包路径的资源，落盘后保持相对结构")
    void materialize_should_keepRelativeLayout_forMultipleResources() {
        GatewayResources resources = new GatewayResources(baseDirectory, classpathLoader());

        Path directory = resources.materialize(new FakeLanguage("python"),
                Arrays.asList(GATEWAY, "script/pkg/helper.py"));

        assertTrue(Files.isRegularFile(directory.resolve(GATEWAY)));
        assertTrue(Files.isRegularFile(directory.resolve("script/pkg/helper.py")));
    }

    @Test
    @DisplayName("默认抽取根目录应在用户主目录下，而不是临时目录")
    void defaultBaseDirectory_should_beUnderUserHome() {
        Path directory = GatewayResources.defaultBaseDirectory();

        assertTrue(directory.startsWith(System.getProperty("user.home")), directory.toString());
    }

    @Test
    @DisplayName("摘要应只由内容与资源名决定，与抽取次数无关")
    void materialize_should_beDeterministic_forSameInputs() {
        GatewayResources resources = new GatewayResources(baseDirectory, classpathLoader());
        FakeLanguage language = new FakeLanguage("python");

        Path first = resources.materialize(language, Collections.singletonList(GATEWAY));
        Path second = resources.materialize(language, new ArrayList<String>(Collections.singletonList(GATEWAY)));

        assertEquals(first.getFileName(), second.getFileName());
    }

    /**
     * 构造从测试类路径读取资源的字节来源。
     *
     * @return 字节来源
     */
    private static GatewayResources.ResourceLoader classpathLoader() {
        return (language, name) -> {
            try (java.io.InputStream stream = GatewayResourcesTest.class.getClassLoader()
                    .getResourceAsStream(name)) {
                if (stream == null) {
                    throw new JellyfishException("网关资源不存在: " + name);
                }
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                int read;
                byte[] chunk = new byte[4096];
                while ((read = stream.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            } catch (java.io.IOException e) {
                throw new JellyfishException("读取网关资源失败: " + name, e);
            }
        };
    }

    /**
     * 假的语言适配：只用来提供语言标识与一个类加载器。
     */
    private static final class FakeLanguage implements ScriptLanguage {

        /** 语言标识。 */
        private final String id;

        /**
         * 构造假适配。
         *
         * @param id 语言标识
         */
        private FakeLanguage(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public List<String> probeCommand() {
            return Collections.singletonList("true");
        }

        @Override
        public List<String> gatewayResources() {
            // 假进程工厂不落盘任何东西，因此空清单就够——真实语言在这里给出自己的网关文件
            return Collections.emptyList();
        }

        @Override
        public List<String> startCommand(Path gatewayDirectory) {
            return Collections.singletonList("true");
        }

        @Override
        public Map<String, String> environment() {
            return new LinkedHashMap<String, String>();
        }
    }
}
