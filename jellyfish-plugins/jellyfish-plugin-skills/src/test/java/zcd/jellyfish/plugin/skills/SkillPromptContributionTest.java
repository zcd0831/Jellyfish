package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillPromptContribution} 的单元测试：清单格式、空清单与描述截断。
 *
 * @author zcd
 */
@DisplayName("skills 清单贡献")
class SkillPromptContributionTest {

    /** 临时根目录。 */
    @TempDir
    Path root;

    @Test
    @DisplayName("没有 skill 时返回空贡献：不留一段「可用 skills：」的废话")
    void handle_should_returnEmpty_when_noSkill() {
        // Given
        SkillPromptContribution contribution =
                new SkillPromptContribution(catalog(Collections.<String, Object>emptyMap()),
                        config(Collections.<String, Object>emptyMap()));

        // When
        PromptContribution result = contribution.handle(new PromptContributionRequest("s1"));

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("清单应给出名称、描述与加载方式")
    void handle_should_listNameAndDescription() throws IOException {
        // Given
        writeSkill("pdf", "---\nname: pdf\ndescription: 处理 PDF\n---\nBODY-CONTENT-MARKER");
        SkillPromptContribution contribution =
                new SkillPromptContribution(catalog(Collections.<String, Object>emptyMap()),
                        config(Collections.<String, Object>emptyMap()));

        // When
        PromptContribution result = contribution.handle(new PromptContributionRequest("s1"));

        // Then
        String text = result.getText();
        assertTrue(text.contains("可用 skills"));
        assertTrue(text.contains(SkillTool.NAME));
        assertTrue(text.contains("- pdf：处理 PDF"));
        // 正文不该出现在常驻清单里，否则渐进披露就白做了
        assertFalse(text.contains("BODY-CONTENT-MARKER"));
    }

    @Test
    @DisplayName("超长描述应被折叠截断，且不把换行带进 system prompt")
    void handle_should_truncateLongDescription() throws IOException {
        // Given
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            description.append("长描述");
        }
        writeSkill("big", "---\nname: big\ndescription: " + description + "\n---\n正文");
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_MAX_DESCRIPTION_CHARS, 20);
        SkillPromptContribution contribution =
                new SkillPromptContribution(catalog(values), config(values));

        // When
        PromptContribution result = contribution.handle(new PromptContributionRequest("s1"));

        // Then
        String text = result.getText();
        assertTrue(text.contains("…"));
        assertFalse(text.contains("\n\n"));
    }

    /**
     * 构造目录缓存。
     *
     * @param overrides 配置覆盖项
     * @return 缓存
     */
    private SkillCatalog catalog(Map<String, Object> overrides) {
        return new SkillCatalog(config(overrides));
    }

    /**
     * 构造只指向临时根目录的配置。
     *
     * @param overrides 配置覆盖项
     * @return 配置
     */
    private SkillsConfig config(Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<String, Object>(overrides);
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
