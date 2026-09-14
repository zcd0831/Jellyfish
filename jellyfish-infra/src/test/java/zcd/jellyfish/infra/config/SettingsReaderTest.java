package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SettingsReader} 的单元测试：覆盖本地文件、classpath 资源与非法路径。
 *
 * @author zcd
 */
class SettingsReaderTest {

    /** 临时目录，用于构造真实的本地配置文件。 */
    @TempDir
    Path tempDir;

    /** 被测读取器。 */
    private final SettingsReader settingsReader = new SettingsReader();

    @Test
    void read_should_return_null_when_path_is_null() {
        assertNull(settingsReader.read(null));
    }

    @Test
    void read_should_return_null_when_path_is_blank() {
        assertNull(settingsReader.read("   "));
    }

    @Test
    void read_should_return_content_when_local_file_exists() throws IOException {
        // Given
        Path file = tempDir.resolve("jellyfish.json");
        Files.write(file, "{\"defaultProvider\":\"openai\"}".getBytes(StandardCharsets.UTF_8));

        // When
        String content = settingsReader.read(file.toString());

        // Then
        assertEquals("{\"defaultProvider\":\"openai\"}", content);
    }

    @Test
    void read_should_return_null_when_local_file_missing() {
        // Given
        Path missing = tempDir.resolve("not-exists.json");

        // When / Then
        assertNull(settingsReader.read(missing.toString()));
    }

    @Test
    void read_should_throw_when_path_is_directory() throws IOException {
        // Given
        Path directory = Files.createDirectory(tempDir.resolve("settings-dir"));

        // When / Then
        assertThrows(JellyfishException.class, () -> settingsReader.read(directory.toString()));
    }

    @Test
    void read_should_return_content_when_classpath_resource_exists() {
        // When
        String content = settingsReader.read("classpath:config.json");

        // Then
        assertTrue(content.contains("\"globalPath\""));
    }

    @Test
    void read_should_return_null_when_classpath_resource_missing() {
        assertNull(settingsReader.read("classpath:not-exists.json"));
    }

    @Test
    void read_should_expand_tilde_when_path_points_into_user_home() throws IOException {
        // Given：把主目录换成临时目录，避免依赖（也不污染）真实主目录
        Files.write(tempDir.resolve("models.json"), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            // When
            String content = settingsReader.read("~/models.json");

            // Then
            assertEquals("{\"a\":1}", content);
        } finally {
            restoreUserHome(original);
        }
    }

    @Test
    void read_should_throw_when_bare_tilde_resolves_to_directory() {
        // Given
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            // When / Then：~ 展开后是目录，与普通目录路径一致地报错
            assertThrows(JellyfishException.class, () -> settingsReader.read("~"));
        } finally {
            restoreUserHome(original);
        }
    }

    @Test
    void read_should_not_expand_tilde_when_path_names_another_user() throws IOException {
        // Given：~other 形式不属于「本用户主目录」，不应被展开
        Files.write(tempDir.resolve("models.json"), "{}".getBytes(StandardCharsets.UTF_8));
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            // When：未展开 → 相对当前工作目录的路径 → 不存在
            String content = settingsReader.read("~nobody/models.json");

            // Then
            assertNull(content);
        } finally {
            restoreUserHome(original);
        }
    }

    /**
     * 还原 {@code user.home}，避免测试间的系统属性污染。
     *
     * @param original 测试前的原值，可为 {@code null}
     */
    private static void restoreUserHome(String original) {
        if (original == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", original);
        }
    }
}
