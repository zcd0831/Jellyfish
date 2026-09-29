package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillsLedger} 的单元测试：台账必须把「根目录状态」与「被跳过的条目」都说出来。
 * <p>
 * 这两件事是 {@code /skills} 存在的全部理由——skill 不生效的现场就是「模型看不见它」，
 * 而能回答为什么的只有这里。
 *
 * @author zcd
 */
@DisplayName("skills 台账")
class SkillsLedgerTest {

    /** 临时根目录。 */
    @TempDir
    Path root;

    @Test
    @DisplayName("应报告根目录、已加载清单与体积")
    void render_should_reportRootsAndSkills() throws IOException {
        // Given
        writeSkill("pdf", "---\nname: pdf\ndescription: 处理 PDF\n---\n正文");
        SkillsConfig config = config();
        SkillCatalog catalog = new SkillCatalog(config);

        // When
        String text = SkillsLedger.render(config, catalog);

        // Then
        assertTrue(text.contains("根目录:"));
        assertTrue(text.contains(root.toString() + "（1 个）"));
        assertTrue(text.contains("已加载 1 个"));
        assertTrue(text.contains("- pdf：处理 PDF"));
    }

    @Test
    @DisplayName("不存在的根目录应明确写出来，而不是留空")
    void render_should_markMissingRoot() {
        // Given
        Path missing = root.resolve("nope");
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList(missing.toString()));
        SkillsConfig config = SkillsConfig.from(values);

        // When
        String text = SkillsLedger.render(config, new SkillCatalog(config));

        // Then
        assertTrue(text.contains("不存在"));
        assertTrue(text.contains("没有任何可用 skill"));
    }

    @Test
    @DisplayName("被跳过的条目应连同原因一起打印")
    void render_should_reportIssues() throws IOException {
        // Given
        writeSkill("no-desc", "---\nname: no-desc\n---\n正文");
        SkillsConfig config = config();

        // When
        String text = SkillsLedger.render(config, new SkillCatalog(config));

        // Then
        assertTrue(text.contains("问题:"));
        assertTrue(text.contains("description"));
    }

    /**
     * 构造只指向临时根目录的配置。
     *
     * @return 配置
     */
    private SkillsConfig config() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList(root.toString()));
        return SkillsConfig.from(values);
    }

    /**
     * 写一个 skill 目录。
     *
     * @param directory 目录名
     * @param content   {@code SKILL.md} 内容
     * @throws IOException 写入失败时抛出
     */
    private void writeSkill(String directory, String content) throws IOException {
        Path skillDir = root.resolve(directory);
        Files.createDirectories(skillDir);
        Files.write(skillDir.resolve(SkillScanner.SKILL_FILE), content.getBytes(StandardCharsets.UTF_8));
    }
}
