package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillScanner} 的单元测试：发现、跳过与逐条目隔离。
 *
 * @author zcd
 */
@DisplayName("skill 目录扫描")
class SkillScannerTest {

    /** 临时根目录。 */
    @TempDir
    Path root;

    /** 被测扫描器。 */
    private final SkillScanner scanner = new SkillScanner();

    @Test
    @DisplayName("子目录里的 SKILL.md 应被发现，名称取自头部")
    void scan_should_discoverSkill_when_directoryHasSkillFile() throws IOException {
        // Given
        writeSkill(root, "pdf", "---\nname: pdf-processing\ndescription: 处理 PDF\n---\n正文");

        // When
        SkillScanResult result = scanDefaultRoots();

        // Then
        assertEquals(1, result.skills().size());
        SkillDefinition skill = result.skills().get(0);
        assertEquals("pdf-processing", skill.name());
        assertEquals("处理 PDF", skill.description());
        assertEquals(root.resolve("pdf"), skill.directory());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    @DisplayName("头部没有 name 时用目录名兜底")
    void scan_should_fallBackToDirectoryName_when_nameMissing() throws IOException {
        // Given
        writeSkill(root, "my-skill", "---\ndescription: 有描述\n---\n正文");

        // When
        SkillScanResult result = scanDefaultRoots();

        // Then
        assertEquals("my-skill", result.skills().get(0).name());
    }

    @Test
    @DisplayName("没有 description 的 skill 应被跳过并记问题：模型没有依据选中它")
    void scan_should_skipSkill_when_descriptionMissing() throws IOException {
        // Given
        writeSkill(root, "no-desc", "---\nname: no-desc\n---\n正文");

        // When
        SkillScanResult result = scanDefaultRoots();

        // Then
        assertTrue(result.skills().isEmpty());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).contains("description"));
    }

    @Test
    @DisplayName("不含 SKILL.md 的子目录直接跳过，不算问题")
    void scan_should_ignoreDirectoryWithoutSkillFile() throws IOException {
        // Given
        Files.createDirectories(root.resolve("plain-dir"));
        Files.write(root.resolve("plain-dir").resolve("readme.md"), "x".getBytes(StandardCharsets.UTF_8));

        // When
        SkillScanResult result = scanDefaultRoots();

        // Then
        assertTrue(result.skills().isEmpty());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    @DisplayName("根目录不存在是正常状态，不记问题")
    void scan_should_ignoreMissingRoot() {
        // Given
        Path missing = root.resolve("nope");

        // When
        SkillScanResult result = scanner.scan(Collections.singletonList(missing), defaultConfig());

        // Then
        assertTrue(result.skills().isEmpty());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    @DisplayName("根目录是文件时应记问题：那是配置写错了，不是没有 skill")
    void scan_should_reportIssue_when_rootIsNotDirectory() throws IOException {
        // Given
        Path file = root.resolve("not-a-dir");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

        // When
        SkillScanResult result = scanner.scan(Collections.singletonList(file), defaultConfig());

        // Then
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).contains("不是目录"));
    }

    @Test
    @DisplayName("同名 skill 先到者胜，后到者记问题")
    void scan_should_keepFirstSkill_when_namesCollide() throws IOException {
        // Given
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        writeSkill(first, "dup", "---\nname: same\ndescription: 先到\n---\nA");
        writeSkill(second, "dup", "---\nname: same\ndescription: 后到\n---\nB");

        // When
        SkillScanResult result = scanner.scan(Arrays.asList(first, second), defaultConfig());

        // Then
        assertEquals(1, result.skills().size());
        assertEquals("先到", result.skills().get(0).description());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).contains("已被更靠前的根目录占用"));
    }

    @Test
    @DisplayName("清单条数受 maxSkills 约束，超出的记问题")
    void scan_should_stopAtMaxSkills() throws IOException {
        // Given
        writeSkill(root, "a", "---\nname: a\ndescription: A\n---\n");
        writeSkill(root, "b", "---\nname: b\ndescription: B\n---\n");
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_MAX_SKILLS, 1);

        // When
        SkillScanResult result = scanner.scan(Collections.singletonList(root), SkillsConfig.from(values));

        // Then
        assertEquals(1, result.skills().size());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("maxSkills")));
    }

    @Test
    @DisplayName("附带文件应递归列出、排除 SKILL.md 本身、按相对路径排序")
    void scan_should_listResourcesRecursively() throws IOException {
        // Given
        Path skillDir = Files.createDirectories(root.resolve("pdf"));
        writeSkill(root, "pdf", "---\nname: pdf\ndescription: 处理 PDF\n---\n正文");
        Files.createDirectories(skillDir.resolve("references"));
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.write(skillDir.resolve("references").resolve("api.md"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(skillDir.resolve("scripts").resolve("merge.py"), "x".getBytes(StandardCharsets.UTF_8));

        // When
        SkillScanResult result = scanDefaultRoots();

        // Then
        List<String> resources = result.skills().get(0).resources();
        assertEquals(Arrays.asList("references/api.md", "scripts/merge.py"), resources);
    }

    @Test
    @DisplayName("正文与附带文件都不进元信息缓存，但体积要算准")
    void scan_should_reportBodyBytes() throws IOException {
        // Given
        writeSkill(root, "s", "---\nname: s\ndescription: D\n---\n12345");

        // When
        SkillScanResult result = scanDefaultRoots();

        // Then
        assertEquals(5, result.skills().get(0).bodyBytes());
    }

    @Test
    @DisplayName("find 命中名称、未命中返回 null")
    void find_should_locateByName() throws IOException {
        // Given
        writeSkill(root, "a", "---\nname: alpha\ndescription: A\n---\n");

        // When
        SkillScanResult result = scanDefaultRoots();

        // Then
        assertNotNull(result.find("alpha"));
        assertNull(result.find("beta"));
        assertNull(result.find(null));
    }

    /**
     * 用默认配置扫描临时根目录。
     *
     * @return 扫描结果
     */
    private SkillScanResult scanDefaultRoots() {
        return scanner.scan(Collections.singletonList(root), defaultConfig());
    }

    /**
     * 构造一个只指向临时根目录的默认配置。
     *
     * @return 配置
     */
    private SkillsConfig defaultConfig() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList(root.toString()));
        return SkillsConfig.from(values);
    }

    /**
     * 在指定根目录下写一个 skill。
     *
     * @param base      根目录
     * @param directory skill 目录名
     * @param content   {@code SKILL.md} 内容
     * @throws IOException 写入失败时抛出
     */
    private static void writeSkill(Path base, String directory, String content) throws IOException {
        Path skillDir = base.resolve(directory);
        Files.createDirectories(skillDir);
        Files.write(skillDir.resolve(SkillScanner.SKILL_FILE), content.getBytes(StandardCharsets.UTF_8));
    }
}
