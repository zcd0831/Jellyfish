package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.args;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.expectFailure;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invoke;

/**
 * {@link WriteFileTool} 的单元测试。
 * <p>
 * 重点锁住三件事：父目录会自动创建、覆盖与新建在输出里必须可区分（覆盖会丢内容，
 * 模型得能自己发现），以及空正文是合法输入。
 *
 * @author zcd
 */
@DisplayName("WriteFileTool 文件写入")
class WriteFileToolTest {

    /** 每个用例独立的临时目录。 */
    @TempDir
    Path tempDir;

    /** 被测工具。 */
    private final WriteFileTool tool = new WriteFileTool();

    @Test
    @DisplayName("写新文件应创建文件并报告「新建」")
    void handle_should_createFile_when_fileMissing() throws Exception {
        Path file = tempDir.resolve("a.txt");

        String output = invoke(tool, args("path", file.toString(), "content", "你好"));

        assertEquals("你好", read(file));
        assertTrue(output.contains("已新建文件"), output);
    }

    @Test
    @DisplayName("父目录不存在应自动创建")
    void handle_should_createParentDirectories_when_missing() throws Exception {
        Path file = tempDir.resolve("nested/deep/a.txt");

        invoke(tool, args("path", file.toString(), "content", "内容"));

        assertEquals("内容", read(file));
    }

    @Test
    @DisplayName("覆盖已有文件应整体替换并报告「覆盖写入」")
    void handle_should_overwrite_when_fileExists() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.write(file, "旧内容".getBytes(StandardCharsets.UTF_8));

        String output = invoke(tool, args("path", file.toString(), "content", "新内容"));

        assertEquals("新内容", read(file));
        assertTrue(output.contains("已覆盖写入"), output);
    }

    @Test
    @DisplayName("空正文是合法输入：清空文件不该被当成参数错误")
    void handle_should_acceptBlankContent() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.write(file, "旧内容".getBytes(StandardCharsets.UTF_8));

        invoke(tool, args("path", file.toString(), "content", ""));

        assertEquals("", read(file));
    }

    @Test
    @DisplayName("内容是目录时应报错")
    void handle_should_fail_when_pathIsDirectory() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", tempDir.toString(), "content", "x")));

        assertTrue(failure.getMessage().contains("无法写入"), failure.getMessage());
    }

    @Test
    @DisplayName("缺少 content 应报错")
    void handle_should_fail_when_contentMissing() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", tempDir.resolve("a.txt").toString())));

        assertEquals("缺少必需参数: content", failure.getMessage());
    }

    /**
     * 读取文件内容。
     *
     * @param file 文件路径
     * @return 内容
     */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取测试产物失败", e);
        }
    }
}
