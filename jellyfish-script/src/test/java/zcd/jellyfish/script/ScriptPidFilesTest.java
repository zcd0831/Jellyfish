package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关 PID 文件路径计算的单元测试。
 * <p>
 * 这些用例守的是两件事：默认目录必须与网关资源目录<b>同级</b>（同级才能在资源摘要变化后
 * 仍然看得见上一代留下的文件），以及语言标识绝不能把文件名变成路径。
 *
 * @author zcd
 */
@DisplayName("网关 PID 文件路径")
class ScriptPidFilesTest {

    @Test
    @DisplayName("默认目录应是用户主目录下的 jellyfish/pids")
    void defaultDirectory_should_beUnderJellyfishHome() {
        assertEquals(Paths.get(System.getProperty("user.home"), "jellyfish", "pids")
                .toAbsolutePath().normalize(), ScriptPidFiles.defaultDirectory());
    }

    @Test
    @DisplayName("默认目录应与网关资源目录同级，而不是它的子目录")
    void defaultDirectory_should_beSiblingOfGatewayResources() {
        // 网关资源目录名带内容摘要，改一个字节就会换目录；PID 文件若挂在它下面，
        // 上一代遗留的那份就永远看不见了
        assertEquals(GatewayResources.defaultBaseDirectory().getParent(),
                ScriptPidFiles.defaultDirectory().getParent());
    }

    @Test
    @DisplayName("文件名应按语言拼成 script-<语言>.pid")
    void fileName_should_carryLanguageId() {
        assertEquals("script-python.pid", ScriptPidFiles.fileName("python"));
    }

    @Test
    @DisplayName("路径应是目录加文件名")
    void pathFor_should_resolveUnderDirectory() {
        assertEquals(Paths.get("/tmp/jf/script-python.pid").toAbsolutePath().normalize(),
                ScriptPidFiles.pathFor(Paths.get("/tmp/jf"), "python").toAbsolutePath().normalize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/b", "a\\b", "..", "../x"})
    @DisplayName("语言标识含路径分隔符时应报错，而不是写到目录外面去")
    void fileName_should_rejectSeparators_when_languageIdContainsThem(String languageId) {
        assertThrows(JellyfishException.class, () -> ScriptPidFiles.fileName(languageId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("语言标识为空白时应报错")
    void fileName_should_rejectBlank_languageId(String languageId) {
        assertThrows(JellyfishException.class, () -> ScriptPidFiles.fileName(languageId));
    }

    @Test
    @DisplayName("语言标识为 null 时应报错，而不是拼出 script-null.pid")
    void fileName_should_rejectNull_languageId() {
        assertThrows(JellyfishException.class, () -> ScriptPidFiles.fileName(null));
    }

    @Test
    @DisplayName("目录为 null 时应报错")
    void pathFor_should_rejectNull_directory() {
        assertThrows(JellyfishException.class, () -> ScriptPidFiles.pathFor(null, "python"));
    }

    @Test
    @DisplayName("语言标识两侧空白应被去掉")
    void fileName_should_trimLanguageId() {
        assertEquals("script-python.pid", ScriptPidFiles.fileName("  python  "));
    }

    @Test
    @DisplayName("未配置 PID 文件时设置为空且不下发该键")
    void settings_should_omitPidFile_whenNotConfigured() {
        GatewaySettings settings = GatewaySettings.defaults();

        assertNull(settings.pidFile());
        assertFalse(settings.toJson().has("pidFile"), settings.toJson().toString());
    }

    @Test
    @DisplayName("配置 PID 文件后应随设置下发")
    void settings_should_carryPidFile_whenConfigured() {
        GatewaySettings settings = GatewaySettings.builder().pidFile("/tmp/jf/script-python.pid").build();

        assertEquals("/tmp/jf/script-python.pid", settings.pidFile());
        assertEquals("/tmp/jf/script-python.pid", settings.toJson().get("pidFile").asText());
        assertTrue(settings.toString().contains("script-python.pid"), settings.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("PID 文件路径为空白应报错，而不是当成「不写」")
    void builder_should_rejectBlank_pidFile(String path) {
        // 静默关掉这个能力会让人以为它启用了，直到真的需要排查线索时才发现什么都没有
        assertThrows(JellyfishException.class, () -> GatewaySettings.builder().pidFile(path));
    }

    @Test
    @DisplayName("显式传 null 应表示不写 PID 文件")
    void builder_should_clearPidFile_when_null() {
        GatewaySettings settings = GatewaySettings.builder()
                .pidFile("/tmp/jf/script-python.pid").pidFile(null).build();

        assertNull(settings.pidFile());
    }
}
