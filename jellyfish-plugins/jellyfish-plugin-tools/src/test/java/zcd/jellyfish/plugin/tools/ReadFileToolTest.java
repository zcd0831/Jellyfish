package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * {@link ReadFileTool} 的单元测试。
 * <p>
 * 重点锁住两件事：分片读取的边界（offset / limit 与截断提示），以及「文件不存在」
 * 「起始行越界」这类错误必须给出可读原因——错误文案是模型自我纠正的唯一依据。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReadFileTool 文件读取")
class ReadFileToolTest {

    /** 每个用例独立的临时目录。 */
    @TempDir
    Path tempDir;

    /** 被测工具，无状态可复用。 */
    private final ReadFileTool tool = new ReadFileTool();

    @Test
    @DisplayName("读取整个文件应原样返回内容，不带行号")
    void handle_should_returnRawContent_when_noRangeGiven() throws Exception {
        Path file = write("a.txt", "第一行\n第二行\n第三行");

        assertEquals("第一行\n第二行\n第三行", invoke(tool, args("path", file.toString())));
    }

    @Test
    @DisplayName("offset 从 1 开始计数，应跳过前面的行")
    void handle_should_skipLeadingLines_when_offsetGiven() throws Exception {
        Path file = write("a.txt", "第一行\n第二行\n第三行");

        assertEquals("第二行\n第三行", invoke(tool, args("path", file.toString(), "offset", 2)));
    }

    @Test
    @DisplayName("limit 生效时应追加续读提示，否则模型会以为文件到此为止")
    void handle_should_addContinueHint_when_limitReached() throws Exception {
        Path file = write("a.txt", "第一行\n第二行\n第三行");

        String output = invoke(tool, args("path", file.toString(), "limit", 2));

        assertTrue(output.startsWith("第一行\n第二行"), output);
        assertTrue(output.contains("已截断"), output);
        assertTrue(output.contains("offset=3"), output);
    }

    @Test
    @DisplayName("刚好读完最后一行时不应出现截断提示")
    void handle_should_notTruncate_when_limitEqualsRemainingLines() throws Exception {
        Path file = write("a.txt", "第一行\n第二行");

        assertEquals("第一行\n第二行", invoke(tool, args("path", file.toString(), "limit", 2)));
    }

    @Test
    @DisplayName("空文件应给出「文件为空」而不是报错")
    void handle_should_reportEmpty_when_fileIsEmpty() throws Exception {
        Path file = write("a.txt", "");

        assertEquals("（文件为空）", invoke(tool, args("path", file.toString())));
    }

    @Test
    @DisplayName("offset 越界应报错并给出总行数")
    void handle_should_fail_when_offsetBeyondLastLine() {
        Path file = write("a.txt", "第一行");

        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", file.toString(), "offset", 9)));

        assertTrue(failure.getMessage().contains("起始行超出文件行数"), failure.getMessage());
        assertTrue(failure.getMessage().contains("共 1 行"), failure.getMessage());
    }

    @Test
    @DisplayName("文件不存在应报错并给出展示路径")
    void handle_should_fail_when_fileMissing() {
        Path missing = tempDir.resolve("missing.txt");

        JellyfishException failure = expectFailure(() -> invoke(tool, args("path", missing.toString())));

        assertTrue(failure.getMessage().contains("文件不存在"), failure.getMessage());
    }

    @Test
    @DisplayName("目标是目录时应提示改用 list_dir")
    void handle_should_fail_when_pathIsDirectory() {
        JellyfishException failure = expectFailure(() -> invoke(tool, args("path", tempDir.toString())));

        assertTrue(failure.getMessage().contains("list_dir"), failure.getMessage());
    }

    @Test
    @DisplayName("offset 小于 1 属于调用错误")
    void handle_should_fail_when_offsetBelowOne() {
        Path file = write("a.txt", "第一行");

        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", file.toString(), "offset", 0)));

        assertTrue(failure.getMessage().contains("offset 必须从 1 开始"), failure.getMessage());
    }

    @Test
    @DisplayName("limit 为负属于调用错误")
    void handle_should_fail_when_limitNegative() {
        Path file = write("a.txt", "第一行");

        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", file.toString(), "limit", -1)));

        assertTrue(failure.getMessage().contains("limit 不能为负数"), failure.getMessage());
    }

    @Test
    @DisplayName("缺少 path 应报错")
    void handle_should_fail_when_pathMissing() {
        JellyfishException failure = expectFailure(() -> invoke(tool, args()));

        assertEquals("缺少必需参数: path", failure.getMessage());
    }

    @Test
    @DisplayName("path 类型不是字符串应报错，而不是当成文件名")
    void handle_should_fail_when_pathNotString() {
        JellyfishException failure = expectFailure(() -> invoke(tool, args("path", 42)));

        assertTrue(failure.getMessage().contains("必须是字符串"), failure.getMessage());
    }

    /**
     * 在临时目录写入文件。
     *
     * @param name    文件名
     * @param content 内容
     * @return 文件路径
     */
    private Path write(String name, String content) {
        try {
            Path file = tempDir.resolve(name);
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("测试前置写入失败", e);
        }
    }
}
