package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpMediaSpill} 的单元测试：落盘、失败退化与清理。
 *
 * @author zcd
 */
@DisplayName("MCP 二进制落盘")
class McpMediaSpillTest {

    /** 临时目录。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("合法 base64 应落盘并返回绝对路径")
    void spill_should_writeFile() throws Exception {
        // Given
        McpMediaSpill spill = new McpMediaSpill(tempDir);

        // When
        String path = spill.spill(Base64.getEncoder().encodeToString("hello".getBytes("UTF-8")),
                "image/png", "fs", "shot");

        // Then
        assertTrue(path.endsWith(".png"));
        assertEquals("hello", new String(Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8"));
    }

    @Test
    @DisplayName("未知 MIME 类型退化成 bin 后缀")
    void spill_should_useBinExtension_forUnknownMimeType() {
        // Given
        McpMediaSpill spill = new McpMediaSpill(tempDir);

        // When
        String path = spill.spill(Base64.getEncoder().encodeToString(new byte[] {1}), "x/y", "fs", "t");

        // Then
        assertTrue(path.endsWith(".bin"));
    }

    @Test
    @DisplayName("非法 base64 与空内容都不落盘，返回 null 让调用点退回「只报告体积」")
    void spill_should_returnNull_forInvalidInput() {
        // Given
        McpMediaSpill spill = new McpMediaSpill(tempDir);

        // Then
        assertNull(spill.spill("!!!not-base64!!!", "image/png", "fs", "t"));
        assertNull(spill.spill(null, "image/png", "fs", "t"));
        assertNull(spill.spill("", "image/png", "fs", "t"));
    }

    @Test
    @DisplayName("cleanup 应删掉本次进程的整个目录；目录不存在时是空操作")
    void cleanup_should_removeDirectory() {
        // Given
        McpMediaSpill spill = new McpMediaSpill(tempDir.resolve("nested"));
        spill.spill(Base64.getEncoder().encodeToString(new byte[] {1}), "image/png", "fs", "t");
        assertTrue(Files.exists(tempDir.resolve("nested")));

        // When
        spill.cleanup();

        // Then
        assertTrue(!Files.exists(tempDir.resolve("nested")));
        spill.cleanup();
    }
}
