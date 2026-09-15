package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.args;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.expectFailure;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invoke;

/**
 * {@link GrepFilesTool} 的单元测试。
 * <p>
 * 重点锁住四件事：输出格式（文件:行号:内容）、跳过名单（构建产物不该污染结果）、
 * 二进制文件不参与匹配、以及达到上限时必须有截断提示——否则模型会把「只显示了 100 条」
 * 当成「总共就 100 条」。
 *
 * @author zcd
 */
@DisplayName("GrepFilesTool 文本搜索")
class GrepFilesToolTest {

    /** 每个用例独立的临时目录。 */
    @TempDir
    Path tempDir;

    /** 被测工具。 */
    private final GrepFilesTool tool = new GrepFilesTool();

    @Test
    @DisplayName("应按「文件:行号:内容」返回匹配行")
    void handle_should_returnFileLineAndText_when_matched() throws Exception {
        write("a.txt", "first\nTODO: 修一下\nlast");

        String output = invoke(tool, args("pattern", "TODO", "path", tempDir.toString()));

        assertTrue(output.contains("a.txt:2:TODO: 修一下"), output);
    }

    @Test
    @DisplayName("没有匹配时应明说，而不是返回空串")
    void handle_should_reportNoMatch_when_nothingMatched() throws Exception {
        write("a.txt", "hello");

        String output = invoke(tool, args("pattern", "zzz", "path", tempDir.toString()));

        assertEquals("没有匹配到任何内容。", output);
    }

    @Test
    @DisplayName("构建产物与版本控制目录应被跳过")
    void handle_should_skipBuildAndVcsDirectories() throws Exception {
        write("src/a.txt", "hit");
        write("target/b.txt", "hit");
        write("node_modules/c.txt", "hit");
        write(".git/d.txt", "hit");

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString()));

        assertTrue(output.contains("a.txt:1:hit"), output);
        assertFalse(output.contains("b.txt"), output);
        assertFalse(output.contains("c.txt"), output);
        assertFalse(output.contains("d.txt"), output);
    }

    @Test
    @DisplayName("二进制文件不应参与匹配")
    void handle_should_skipBinaryFiles() throws Exception {
        Path binary = tempDir.resolve("blob.bin");
        try (OutputStream output = Files.newOutputStream(binary)) {
            output.write(new byte[]{'h', 'i', 't', 0, 'h', 'i', 't'});
        }

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString()));

        assertEquals("没有匹配到任何内容。", output);
    }

    @Test
    @DisplayName("达到 max_results 时应截断并提示")
    void handle_should_truncate_when_maxResultsReached() throws Exception {
        write("a.txt", "hit\nhit\nhit");

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString(), "max_results", 2));

        assertTrue(output.contains("a.txt:1:hit"), output);
        assertTrue(output.contains("已截断"), output);
        assertFalse(output.contains("a.txt:3:hit"), output);
    }

    @Test
    @DisplayName("匹配数刚好等于上限时不应报截断")
    void handle_should_notTruncate_when_matchesEqualLimit() throws Exception {
        write("a.txt", "hit\nhit");

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString(), "max_results", 2));

        assertFalse(output.contains("已截断"), output);
    }

    @Test
    @DisplayName("起点是单个文件时只搜该文件")
    void handle_should_searchSingleFile_when_pathIsFile() throws Exception {
        Path file = write("a.txt", "hit");
        write("b.txt", "hit");

        String output = invoke(tool, args("pattern", "hit", "path", file.toString()));

        assertTrue(output.contains("a.txt:1:hit"), output);
        assertFalse(output.contains("b.txt"), output);
    }

    @Test
    @DisplayName("pattern 是正则而非字面量")
    void handle_should_treatPatternAsRegex() throws Exception {
        write("a.txt", "count = 42");

        String output = invoke(tool, args("pattern", "=\\s*\\d+", "path", tempDir.toString()));

        assertTrue(output.contains("a.txt:1:count = 42"), output);
    }

    @Test
    @DisplayName("正则非法应报错")
    void handle_should_fail_when_patternInvalid() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("pattern", "[unclosed", "path", tempDir.toString())));

        assertTrue(failure.getMessage().contains("正则表达式非法"), failure.getMessage());
    }

    @Test
    @DisplayName("max_results 小于 1 属于调用错误")
    void handle_should_fail_when_maxResultsBelowOne() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("pattern", "x", "path", tempDir.toString(), "max_results", 0)));

        assertTrue(failure.getMessage().contains("max_results 必须大于 0"), failure.getMessage());
    }

    @Test
    @DisplayName("路径不存在应报错")
    void handle_should_fail_when_pathMissing() {
        Path missing = tempDir.resolve("missing");

        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("pattern", "x", "path", missing.toString())));

        assertTrue(failure.getMessage().contains("路径不存在"), failure.getMessage());
    }

    @Test
    @DisplayName("缺少 pattern 应报错")
    void handle_should_fail_when_patternMissing() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", tempDir.toString())));

        assertEquals("缺少必需参数: pattern", failure.getMessage());
    }

    /**
     * 在临时目录写入文件，自动创建父目录。
     *
     * @param relativePath 相对路径
     * @param content      内容
     * @return 文件路径
     */
    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
