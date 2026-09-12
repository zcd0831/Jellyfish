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
}
