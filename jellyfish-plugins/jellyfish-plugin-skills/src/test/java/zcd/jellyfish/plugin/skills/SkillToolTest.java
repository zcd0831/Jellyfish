package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillTool} 的单元测试：命中、未命中、截断与参数校验。
 *
 * @author zcd
 */
@DisplayName("skill 工具")
class SkillToolTest {

    /** 临时根目录。 */
    @TempDir
    Path root;

    @Test
    @DisplayName("命中时应回灌正文、目录绝对路径与附带文件清单")
    void handle_should_returnBodyAndResources_when_skillFound() throws IOException {
        // Given
        Path skillDir = Files.createDirectories(root.resolve("pdf"));
        write(skillDir.resolve(SkillScanner.SKILL_FILE), "---\nname: pdf\ndescription: D\n---\n# 用法\n第一步");
        write(skillDir.resolve("refs.md"), "x");
        SkillTool tool = newTool(Collections.<String, Object>emptyMap());

        // When
        ToolCallResult result = tool.handle(new ToolCallRequest(SkillTool.NAME,
                Collections.<String, Object>singletonMap("name", "pdf"), "s1"));

        // Then：目录必须给绝对路径，否则正文里的相对引用落不到实处
        assertTrue(result.getOutput().toString().contains("[skill: pdf]" + "（目录：" + skillDir + "）"));
        assertTrue(result.getOutput().toString().contains("# 用法\n第一步"));
        assertTrue(result.getOutput().toString().contains("- refs.md"));
        assertTrue(String.valueOf(result.getMetadata().get(ToolMetadata.KEY_SUMMARY)).contains("pdf"));
    }

    @Test
    @DisplayName("未命中时应列出可用名称，便于模型自我纠正")
    void handle_should_listNames_when_skillMissing() throws IOException {
        // Given
        Path skillDir = Files.createDirectories(root.resolve("pdf"));
        write(skillDir.resolve(SkillScanner.SKILL_FILE), "---\nname: pdf\ndescription: D\n---\n正文");
        SkillTool tool = newTool(Collections.<String, Object>emptyMap());

        // When
        ToolCallResult result = tool.handle(new ToolCallRequest(SkillTool.NAME,
                Collections.<String, Object>singletonMap("name", "nope"), "s1"));

        // Then
        assertTrue(result.getOutput().toString().contains("没有名为 nope 的 skill"));
        assertTrue(result.getOutput().toString().contains("pdf"));
    }

    @Test
    @DisplayName("一个 skill 都没有时也给一条可读的回复")
    void handle_should_sayNoSkillAvailable_when_catalogEmpty() {
        // Given
        SkillTool tool = newTool(Collections.<String, Object>emptyMap());

        // When
        ToolCallResult result = tool.handle(new ToolCallRequest(SkillTool.NAME,
                Collections.<String, Object>singletonMap("name", "nope"), "s1"));

        // Then
        assertTrue(result.getOutput().toString().contains("当前也没有任何可用的 skill"));
    }

    @Test
    @DisplayName("正文未超上限时不应截断")
    void handle_should_notTruncateBody_when_underLimit() throws IOException {
        // Given：正文 200 字节，上限压到 1024
        Path skillDir = Files.createDirectories(root.resolve("big"));
        StringBuilder body = new StringBuilder("---\nname: big\ndescription: D\n---\n");
        for (int i = 0; i < 200; i++) {
            body.append('x');
        }
        write(skillDir.resolve(SkillScanner.SKILL_FILE), body.toString());
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_MAX_BODY_BYTES, 1024);
        SkillTool tool = newTool(values);

        // When
        ToolCallResult result = tool.handle(new ToolCallRequest(SkillTool.NAME,
                Collections.<String, Object>singletonMap("name", "big"), "s1"));

        // Then：200 字节没到 1024，不该截断
        assertTrue(!result.getOutput().toString().contains("已截断"));
    }

    @Test
    @DisplayName("正文确实超限时应截断，且不切出半个多字节字符")
    void handle_should_truncateAtCharacterBoundary() throws IOException {
        // Given：正文是 2000 个中文字符（6000 字节），上限 1024 字节
        Path skillDir = Files.createDirectories(root.resolve("cn"));
        StringBuilder body = new StringBuilder("---\nname: cn\ndescription: D\n---\n");
        for (int i = 0; i < 2000; i++) {
            body.append('中');
        }
        write(skillDir.resolve(SkillScanner.SKILL_FILE), body.toString());
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_MAX_BODY_BYTES, 1024);
        SkillTool tool = newTool(values);

        // When
        ToolCallResult result = tool.handle(new ToolCallRequest(SkillTool.NAME,
                Collections.<String, Object>singletonMap("name", "cn"), "s1"));

        // Then
        String output = result.getOutput().toString();
        assertTrue(output.contains("已截断"));
        assertTrue(!output.contains("\uFFFD"));
    }

    @Test
    @DisplayName("缺少 name 参数应抛错，由内核转成可读的工具结果")
    void handle_should_throw_when_nameMissing() {
        // Given
        SkillTool tool = newTool(Collections.<String, Object>emptyMap());

        // When / Then
        assertThrows(JellyfishException.class,
                () -> tool.handle(new ToolCallRequest(SkillTool.NAME, Collections.<String, Object>emptyMap(), "s1")));
        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(SkillTool.NAME,
                Collections.<String, Object>singletonMap("name", "  "), "s1")));
        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(SkillTool.NAME,
                Collections.<String, Object>singletonMap("name", 42), "s1")));
    }

    @Test
    @DisplayName("工具名片应声明为只读，PLAN 模式下才能加载说明")
    void descriptor_should_beReadOnly() {
        // Then
        assertTrue(newTool(Collections.<String, Object>emptyMap()).descriptor().isReadOnly());
        assertEquals(SkillTool.NAME, newTool(Collections.<String, Object>emptyMap()).descriptor().getName());
    }

    @Test
    @DisplayName("正文读取失败应抛错而不是回一条空结果")
    void handle_should_throw_when_bodyUnreadable() throws IOException {
        // Given：扫描期已经认为这个 skill 存在，而读取期文件已不在。
        // 真实目录缓存会因为文件消失而重扫（那是正确行为），因此这里用一个固定快照的替身，
        // 专测「拿到了定义但读不到正文」这条分支
        Path skillDir = Files.createDirectories(root.resolve("gone"));
        Path body = skillDir.resolve(SkillScanner.SKILL_FILE);
        SkillDefinition definition = new SkillDefinition("gone", "D", skillDir, body, 6,
                Collections.<String>emptyList(), false);
        SkillCatalog catalog = Mockito.mock(SkillCatalog.class);
        Mockito.when(catalog.current()).thenReturn(new SkillScanResult(
                Collections.singletonList(definition), Collections.<String>emptyList()));
        SkillTool tool = new SkillTool(catalog, config(Collections.<String, Object>emptyMap()));

        // When / Then
        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(SkillTool.NAME,
                Collections.<String, Object>singletonMap("name", "gone"), "s1")));
    }

    /**
     * 构造只指向临时根目录的工具。
     *
     * @param overrides 配置覆盖项
     * @return 工具
     */
    private SkillTool newTool(Map<String, Object> overrides) {
        SkillsConfig config = config(overrides);
        return new SkillTool(new SkillCatalog(config), config);
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
     * 写文件（自动创建父目录）。
     *
     * @param path    路径
     * @param content 内容
     * @throws IOException 写入失败时抛出
     */
    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
