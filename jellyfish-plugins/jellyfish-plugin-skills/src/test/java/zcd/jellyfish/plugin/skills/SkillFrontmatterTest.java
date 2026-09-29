package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillFrontmatter} 的单元测试：围栏、扁平键值、折行值与正文切分。
 *
 * @author zcd
 */
@DisplayName("SKILL.md 头部解析")
class SkillFrontmatterTest {

    @Test
    @DisplayName("标准头部应取出 name 与 description，正文从围栏之后开始")
    void parse_should_extractKeysAndBody_when_frontmatterPresent() {
        // Given
        String content = "---\nname: pdf-processing\ndescription: 处理 PDF\n---\n# 用法\n第一步";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertEquals("pdf-processing", parsed.name());
        assertEquals("处理 PDF", parsed.description());
        assertEquals("# 用法\n第一步", parsed.body());
    }

    @Test
    @DisplayName("没有头部时整份内容都是正文，名称与描述均为空")
    void parse_should_treatWholeContentAsBody_when_noFrontmatter() {
        // Given
        String content = "# 就是一段说明\n没有头部";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertNull(parsed.name());
        assertNull(parsed.description());
        assertEquals(content, parsed.body());
    }

    @Test
    @DisplayName("围栏没闭合时按「没有头部」处理，而不是把正文整段吃掉")
    void parse_should_fallBackToBodyOnly_when_fenceNotClosed() {
        // Given
        String content = "---\nname: broken\n正文其实在这里";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertNull(parsed.name());
        assertEquals(content, parsed.body());
    }

    @Test
    @DisplayName("块标量标记后的缩进行应折成一段描述")
    void parse_should_foldIndentedBlock_when_valueUsesBlockMarker() {
        // Given
        String content = "---\nname: x\ndescription: >\n  第一行\n  第二行\n---\n正文";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertEquals("第一行 第二行", parsed.description());
        assertEquals("正文", parsed.body());
    }

    @Test
    @DisplayName("键后的空值同样按折行块读取")
    void parse_should_foldIndentedBlock_when_valueIsEmpty() {
        // Given
        String content = "---\ndescription:\n  只有折行\n---\n正文";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertEquals("只有折行", parsed.description());
    }

    @Test
    @DisplayName("成对引号应被去掉")
    void parse_should_unquoteValues() {
        // Given
        String content = "---\nname: \"quoted\"\ndescription: 'single'\n---\n";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertEquals("quoted", parsed.name());
        assertEquals("single", parsed.description());
    }

    @Test
    @DisplayName("键名大小写不敏感")
    void parse_should_matchKeysCaseInsensitively() {
        // Given
        String content = "---\nName: upper\nDescription: desc\n---\n";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertEquals("upper", parsed.name());
        assertEquals("desc", parsed.description());
    }

    @Test
    @DisplayName("BOM 不应让围栏判断失败")
    void parse_should_tolerateBom() {
        // Given
        String content = "\uFEFF---\nname: bom\n---\n正文";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertEquals("bom", parsed.name());
        assertEquals("正文", parsed.body());
    }

    @Test
    @DisplayName("空白值归一为 null，调用方据此判定「没有描述」")
    void parse_should_normalizeBlankValuesToNull() {
        // Given
        String content = "---\nname: \"  \"\ndescription:\n---\n";

        // When
        SkillFrontmatter parsed = SkillFrontmatter.parse(content);

        // Then
        assertNull(parsed.name());
        assertNull(parsed.description());
    }

    @Test
    @DisplayName("空内容返回空结果而不是抛错")
    void parse_should_returnEmpty_when_contentIsNullOrEmpty() {
        // When / Then
        assertNull(SkillFrontmatter.parse(null).name());
        assertEquals("", SkillFrontmatter.parse("").body());
        assertTrue(SkillFrontmatter.parse(null).body().isEmpty());
    }
}
