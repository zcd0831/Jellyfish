package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.codec.ExtensionCodecs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本目录扫描的单元测试。
 * <p>
 * 三条行为值得单独盯住，因为它们直接决定「用户看到的是一句可行动的报错，还是工具莫名消失」：
 * 目录不存在属正常冷启动、逐脚本问题不影响其它脚本、入口文件缺失必须在启动期就报出来
 * （否则要等到用户第一次调用该工具才发现）。
 *
 * @author zcd
 */
@DisplayName("脚本目录扫描")
class ScriptPluginScannerTest {

    /** 测试用脚本根目录。 */
    @TempDir
    Path scriptsRoot;

    /** 被测扫描器。 */
    private final ScriptPluginScanner scanner = new ScriptPluginScanner(ExtensionCodecs.DEFAULTS);

    @Test
    @DisplayName("根目录不存在时应产出空结果，而不是报错")
    void scan_should_returnEmpty_when_rootDoesNotExist() {
        ScriptScanResult result = scanner.scan(scriptsRoot.resolve("never-created"));

        assertTrue(result.plugins().isEmpty());
        assertTrue(result.issues().isEmpty());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("根目录存在但不是目录时属全局故障，应抛错")
    void scan_should_throwJellyfishException_when_rootIsNotDirectory() throws IOException {
        Path file = Files.write(scriptsRoot.resolve("not-a-dir"), new byte[0]);

        assertThrows(JellyfishException.class, () -> scanner.scan(file));
    }

    @Test
    @DisplayName("合法脚本应被加载，id 缺省用目录名")
    void scan_should_loadScript_when_manifestAndEntryExist() throws IOException {
        writeScript("jira", "{\"entry\":\"main.py\"}", "main.py");

        ScriptScanResult result = scanner.scan(scriptsRoot);

        assertEquals(1, result.plugins().size());
        assertEquals("jira", result.plugins().get(0).id());
        assertEquals(scriptsRoot.resolve("jira").resolve("main.py"), result.plugins().get(0).entryFile());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    @DisplayName("缺 manifest.json 的目录应记问题并跳过，不影响其它脚本")
    void scan_should_recordIssue_when_manifestIsMissing() throws IOException {
        Files.createDirectories(scriptsRoot.resolve("broken"));
        writeScript("jira", "{\"entry\":\"main.py\"}", "main.py");

        ScriptScanResult result = scanner.scan(scriptsRoot);

        assertEquals(1, result.plugins().size());
        assertEquals(1, result.issues().size());
        assertEquals("broken", result.issues().get(0).source());
        assertTrue(result.issues().get(0).message().contains(ScriptManifest.FILE_NAME));
    }

    @Test
    @DisplayName("清单写法有误的脚本应记问题，同目录其它脚本照常加载")
    void scan_should_isolateInvalidManifest_when_oneScriptIsBroken() throws IOException {
        writeScript("broken", "{\"entry\":\"main.py\",\"command\":[]}", "main.py");
        writeScript("jira", "{\"entry\":\"main.py\"}", "main.py");

        ScriptScanResult result = scanner.scan(scriptsRoot);

        assertEquals(1, result.plugins().size());
        assertEquals("jira", result.plugins().get(0).id());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).message().contains("command"), result.issues().toString());
    }

    @Test
    @DisplayName("入口文件缺失应在扫描期就报问题，而不是拖到第一次调用")
    void scan_should_recordIssue_when_entryFileIsMissing() throws IOException {
        writeScript("jira", "{\"entry\":\"main.py\"}");

        ScriptScanResult result = scanner.scan(scriptsRoot);

        assertTrue(result.plugins().isEmpty());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).message().contains("入口文件不存在"));
    }

    @Test
    @DisplayName("两个目录声明同一 id 时应保留先出现的，后一个记问题")
    void scan_should_recordIssue_when_twoDirectoriesShareId() throws IOException {
        writeScript("a-first", "{\"id\":\"same\",\"entry\":\"main.py\"}", "main.py");
        writeScript("b-second", "{\"id\":\"same\",\"entry\":\"main.py\"}", "main.py");

        ScriptScanResult result = scanner.scan(scriptsRoot);

        assertEquals(1, result.plugins().size());
        assertEquals("a-first", result.plugins().get(0).directory().getFileName().toString());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).message().contains("脚本标识重复"));
    }

    @Test
    @DisplayName("应忽略根目录下的散落文件")
    void scan_should_ignorePlainFiles_when_rootHasStrayFile() throws IOException {
        Files.write(scriptsRoot.resolve("readme.txt"), "x".getBytes(StandardCharsets.UTF_8));
        writeScript("jira", "{\"entry\":\"main.py\"}", "main.py");

        ScriptScanResult result = scanner.scan(scriptsRoot);

        assertEquals(1, result.plugins().size());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    @DisplayName("多个脚本应按目录名升序出现，保证跳过谁在各机器上一致")
    void scan_should_sortPlugins_by_directoryName() throws IOException {
        writeScript("charlie", "{\"entry\":\"main.py\"}", "main.py");
        writeScript("alpha", "{\"entry\":\"main.py\"}", "main.py");
        writeScript("bravo", "{\"entry\":\"main.py\"}", "main.py");

        ScriptScanResult result = scanner.scan(scriptsRoot);

        List<String> ids = new ArrayList<String>();
        for (ScriptPlugin plugin : result.plugins()) {
            ids.add(plugin.id());
        }
        assertEquals(java.util.Arrays.asList("alpha", "bravo", "charlie"), ids);
    }

    /**
     * 在根目录下写一个脚本目录。
     *
     * @param directory 目录名
     * @param manifest  清单正文
     * @param entries   需要创建的入口文件
     * @throws IOException 写入失败时抛出
     */
    private void writeScript(String directory, String manifest, String... entries) throws IOException {
        Path target = scriptsRoot.resolve(directory);
        Files.createDirectories(target);
        Files.write(target.resolve(ScriptManifest.FILE_NAME), manifest.getBytes(StandardCharsets.UTF_8));
        for (String entry : entries) {
            Files.write(target.resolve(entry), "".getBytes(StandardCharsets.UTF_8));
        }
    }
}
