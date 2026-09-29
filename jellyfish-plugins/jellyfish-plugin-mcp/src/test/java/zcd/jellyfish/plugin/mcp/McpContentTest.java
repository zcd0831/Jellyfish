package zcd.jellyfish.plugin.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpContent} 的单元测试：内容块按类型渲染，二进制落盘而不是塞 base64。
 *
 * @author zcd
 */
@DisplayName("MCP 工具结果渲染")
class McpContentTest {

    /** 临时目录。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("多个文本块应换行拼接")
    void render_should_joinTextBlocks() {
        // Given
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"第一行\"},"
                + "{\"type\":\"text\",\"text\":\"第二行\"}]}";

        // Then
        assertEquals("第一行\n第二行", render(json));
    }

    @Test
    @DisplayName("图片内容应落盘并给出路径，而不是把 base64 塞进上下文")
    void render_should_spillImage() throws Exception {
        // Given
        byte[] bytes = {1, 2, 3, 4, 5};
        String json = "{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}]}";

        // When
        String text = render(json);

        // Then
        assertTrue(text.contains("image 内容已保存"));
        assertTrue(text.contains(".png"));
        String path = text.substring(text.indexOf("已保存：") + 4, text.indexOf("（image"));
        assertTrue(Files.exists(java.nio.file.Paths.get(path)));
    }

    @Test
    @DisplayName("资源块有内联正文时给正文，没有则给 URI 并说明需另行读取")
    void render_should_renderResourceBlocks() {
        // Given
        String withText = "{\"content\":[{\"type\":\"resource\",\"resource\":"
                + "{\"uri\":\"file:///a.md\",\"text\":\"正文在此\"}}]}";
        String withoutText = "{\"content\":[{\"type\":\"resource\",\"resource\":"
                + "{\"uri\":\"file:///b.md\"}}]}";

        // Then
        assertTrue(render(withText).contains("正文在此"));
        assertTrue(render(withText).contains("file:///a.md"));
        assertTrue(render(withoutText).contains("未内联"));
    }

    @Test
    @DisplayName("未知类型不丢：退化成 JSON 摘要并说明这是什么")
    void render_should_fallBackToJsonForUnknownType() {
        // Given
        String json = "{\"content\":[{\"type\":\"future-thing\",\"payload\":{\"a\":1}}]}";

        // Then：丢掉它会让模型看到一份看似正常、实则缺了内容的结果
        String text = render(json);
        assertTrue(text.contains("future-thing"));
        assertTrue(text.contains("\"a\":1"));
    }

    @Test
    @DisplayName("没有 content 时给出整个结果的 JSON 摘要")
    void render_should_summarizeWholeResult_when_contentMissing() {
        // Given
        String json = "{\"structuredContent\":{\"ok\":true}}";

        // Then
        assertTrue(render(json).contains("structuredContent"));
    }

    @Test
    @DisplayName("空结果给一句明确的话，而不是空串")
    void render_should_saySomething_when_resultNull() {
        // Then
        assertTrue(McpContent.render(null, spill(), "fs", "t").contains("空结果"));
    }

    /**
     * 渲染一段结果 JSON。
     *
     * @param json 结果 JSON
     * @return 渲染文本
     */
    private String render(String json) {
        JsonNode node = McpJson.parse(json);
        return McpContent.render(node, spill(), "fs", "t");
    }

    /**
     * 构造落盘器。
     *
     * @return 落盘器
     */
    private McpMediaSpill spill() {
        return new McpMediaSpill(tempDir);
    }
}
