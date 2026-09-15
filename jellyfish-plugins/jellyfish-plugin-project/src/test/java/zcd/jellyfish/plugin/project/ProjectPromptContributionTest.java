package zcd.jellyfish.plugin.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectPromptContribution} 的单元测试。
 * <p>
 * 最关键的一条是「**不含正文**」：只给路径是本插件的核心约束，用哨兵字符串把它钉住——
 * 将来若有人图省事改成「顺手把内容也带上」，这条用例会立刻红。
 *
 * @author zcd
 */
@DisplayName("项目约定提示词贡献")
class ProjectPromptContributionTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    @Test
    @DisplayName("没有约定文件时返回空贡献：内核不会追加任何块，连空标题都不会有")
    void handle_should_returnEmpty_when_noConventionFile() {
        PromptContribution result = contribution().handle(new PromptContributionRequest("s-1"));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("有约定文件时应给出带路径的指引块")
    void handle_should_containPath_when_fileExists() throws IOException {
        writeConventionFile("随便什么内容");

        PromptContribution result = contribution().handle(new PromptContributionRequest("s-1"));

        assertFalse(result.isEmpty());
        String text = result.getText();
        assertTrue(text.startsWith("[项目约定]"), text);
        assertTrue(text.contains("AGENTS.md"), text);
        assertTrue(text.contains("不覆盖你的安全底线"), text);
    }

    @Test
    @DisplayName("指引里不得出现约定文件正文：第三方仓库的内容不该拿到 system prompt 的话语权")
    void handle_should_notContainFileBody_when_fileExists() throws IOException {
        writeConventionFile("SENTINEL-BODY-9f3a 这段正文绝不能出现在指引里");

        String text = contribution().handle(new PromptContributionRequest("s-1")).getText();

        assertFalse(text.contains("SENTINEL-BODY-9f3a"), text);
    }

    @Test
    @DisplayName("不缓存：会话中途新建约定文件，下一次询问就会命中")
    void handle_should_detectNewFile_onNextCall() throws IOException {
        assertTrue(contribution().handle(new PromptContributionRequest("s-1")).isEmpty());

        writeConventionFile("内容");

        assertFalse(contribution().handle(new PromptContributionRequest("s-1")).isEmpty());
    }

    /**
     * 构造指向当前临时目录的贡献处理器。
     *
     * @return 被测处理器
     */
    private ProjectPromptContribution contribution() {
        return new ProjectPromptContribution(new ConventionFiles(directory));
    }

    /**
     * 在基准目录里写一份约定文件。
     *
     * @param content 文件内容
     * @throws IOException 写入失败时抛出
     */
    private void writeConventionFile(String content) throws IOException {
        Files.write(directory.resolve(ConventionFiles.CONVENTION_FILE), content.getBytes("UTF-8"));
    }
}
