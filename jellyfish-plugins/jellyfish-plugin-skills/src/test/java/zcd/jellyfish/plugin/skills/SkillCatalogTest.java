package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SkillCatalog} 的单元测试：缓存命中与按文件系统签名失效。
 * <p>
 * 失效必须真的生效，否则用户会遇到「我改了 SKILL.md 却什么都没发生」——而 {@code /reload}
 * 只重启配置段变了的插件，救不了这一种。
 *
 * @author zcd
 */
@DisplayName("skill 目录缓存")
class SkillCatalogTest {

    /** 临时根目录。 */
    @TempDir
    Path root;

    @Test
    @DisplayName("文件系统没变时应复用同一份扫描结果")
    void current_should_reuseSnapshot_when_filesystemUnchanged() {
        // Given
        SkillCatalog catalog = newCatalog();

        // When
        SkillScanResult first = catalog.current();
        SkillScanResult second = catalog.current();

        // Then
        assertSame(first, second);
    }

    @Test
    @DisplayName("新增 skill 目录（根目录 mtime 变化）应触发重扫")
    void current_should_rescan_when_newSkillDirectoryAppears() throws IOException {
        // Given
        writeSkill("a", "---\nname: a\ndescription: A\n---\n");
        SkillCatalog catalog = newCatalog();
        assertEquals(1, catalog.current().skills().size());

        // When
        writeSkill("b", "---\nname: b\ndescription: B\n---\n");
        touch(root);

        // Then
        assertEquals(2, catalog.current().skills().size());
    }

    @Test
    @DisplayName("就地编辑 SKILL.md（文件 mtime 变化）应触发重扫")
    void current_should_rescan_when_skillFileEdited() throws IOException {
        // Given
        Path body = writeSkill("a", "---\nname: a\ndescription: 旧描述\n---\n");
        SkillCatalog catalog = newCatalog();
        assertEquals("旧描述", catalog.current().skills().get(0).description());

        // When
        Files.write(body, "---\nname: a\ndescription: 新描述\n---\n".getBytes(StandardCharsets.UTF_8));
        touch(body);

        // Then
        SkillScanResult rescanned = catalog.current();
        assertEquals("新描述", rescanned.skills().get(0).description());
    }

    @Test
    @DisplayName("重扫后签名会更新，因此再问一次不会再扫一遍")
    void current_should_settleAfterRescan() throws IOException {
        // Given
        Path body = writeSkill("a", "---\nname: a\ndescription: 旧描述\n---\n");
        SkillCatalog catalog = newCatalog();
        catalog.current();
        Files.write(body, "---\nname: a\ndescription: 新描述\n---\n".getBytes(StandardCharsets.UTF_8));
        touch(body);

        // When
        SkillScanResult rescanned = catalog.current();
        SkillScanResult again = catalog.current();

        // Then
        assertNotNull(rescanned);
        assertSame(rescanned, again);
    }

    @Test
    @DisplayName("删除 skill 目录（根目录 mtime 变化）应让清单收缩")
    void current_should_rescan_when_skillDirectoryRemoved() throws IOException {
        // Given
        writeSkill("a", "---\nname: a\ndescription: A\n---\n");
        Path doomed = writeSkill("b", "---\nname: b\ndescription: B\n---\n").getParent();
        SkillCatalog catalog = newCatalog();
        assertEquals(2, catalog.current().skills().size());

        // When
        deleteRecursively(doomed);
        touch(root);

        // Then
        assertEquals(1, catalog.current().skills().size());
    }

    /**
     * 构造只指向临时根目录的目录缓存。
     *
     * @return 缓存
     */
    private SkillCatalog newCatalog() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList(root.toString()));
        return new SkillCatalog(SkillsConfig.from(values));
    }

    /**
     * 写一个 skill 目录。
     *
     * @param directory 目录名
     * @param content   {@code SKILL.md} 内容
     * @return {@code SKILL.md} 路径
     * @throws IOException 写入失败时抛出
     */
    private Path writeSkill(String directory, String content) throws IOException {
        Path skillDir = root.resolve(directory);
        Files.createDirectories(skillDir);
        Path body = skillDir.resolve(SkillScanner.SKILL_FILE);
        Files.write(body, content.getBytes(StandardCharsets.UTF_8));
        return body;
    }

    /**
     * 把路径的修改时间推到未来。
     * <p>
     * 显式设置而不是依赖「写完就变了」：部分文件系统的时间戳粒度较粗，连续两次写入可能落在
     * 同一个刻度上，那时用例会随机地不触发重扫。
     *
     * @param path 路径
     * @throws IOException 设置失败时抛出
     */
    private static void touch(Path path) throws IOException {
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 10_000L));
    }

    /**
     * 递归删除目录。
     *
     * @param directory 目录
     * @throws IOException 删除失败时抛出
     */
    private static void deleteRecursively(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            java.util.List<Path> all = new java.util.ArrayList<Path>();
            for (Path path : (Iterable<Path>) paths::iterator) {
                all.add(path);
            }
            Collections.reverse(all);
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
        }
    }
}
