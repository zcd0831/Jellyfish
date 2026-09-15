package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.args;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.expectFailure;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invoke;

/**
 * {@link ListDirTool} 的单元测试。
 * <p>
 * 重点锁住排序口径（目录优先、同类按名字）与「不递归」：这两点决定了模型怎么理解项目结构。
 *
 * @author zcd
 */
@DisplayName("ListDirTool 目录列举")
class ListDirToolTest {

    /** 每个用例独立的临时目录。 */
    @TempDir
    Path tempDir;

    /** 被测工具。 */
    private final ListDirTool tool = new ListDirTool();

    @Test
    @DisplayName("目录应排在同级文件之前，且带 / 后缀")
    void handle_should_listDirectoriesBeforeFiles() throws Exception {
        Files.createDirectory(tempDir.resolve("zebra"));
        write("alpha.txt", "x");
        write("beta.txt", "x");

        String output = invoke(tool, args("path", tempDir.toString()));

        int zebra = output.indexOf("zebra/");
        int alpha = output.indexOf("alpha.txt");
        int beta = output.indexOf("beta.txt");
        assertTrue(zebra > 0, output);
        assertTrue(zebra < alpha, output);
        assertTrue(alpha < beta, output);
    }

    @Test
    @DisplayName("文件应带字节数，目录不带")
    void handle_should_showFileSizeOnly() throws Exception {
        write("a.txt", "abc");

        String output = invoke(tool, args("path", tempDir.toString()));

        assertTrue(output.contains("f  a.txt  (3 字节)"), output);
    }

    @Test
    @DisplayName("空目录应明说「空目录」而不是给一个空输出")
    void handle_should_reportEmpty_when_directoryHasNoEntries() throws Exception {
        String output = invoke(tool, args("path", tempDir.toString()));

        assertTrue(output.contains("空目录"), output);
    }

    @Test
    @DisplayName("只列一层：子目录里的内容不应出现在输出里")
    void handle_should_notRecurse() throws Exception {
        Path nested = Files.createDirectory(tempDir.resolve("nested"));
        Files.write(nested.resolve("deep.txt"), "x".getBytes(StandardCharsets.UTF_8));

        String output = invoke(tool, args("path", tempDir.toString()));

        assertTrue(output.contains("nested/"), output);
        assertFalse(output.contains("deep.txt"), output);
    }

    @Test
    @DisplayName("目标是文件时应提示改用 read_file")
    void handle_should_fail_when_pathIsFile() throws Exception {
        write("a.txt", "x");

        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", tempDir.resolve("a.txt").toString())));

        assertTrue(failure.getMessage().contains("read_file"), failure.getMessage());
    }

    @Test
    @DisplayName("目录不存在应报错")
    void handle_should_fail_when_directoryMissing() {
        Path missing = tempDir.resolve("missing");

        JellyfishException failure = expectFailure(() -> invoke(tool, args("path", missing.toString())));

        assertTrue(failure.getMessage().contains("目录不存在"), failure.getMessage());
    }

    /**
     * 在临时目录写入文件。
     *
     * @param name    文件名
     * @param content 内容
     */
    private void write(String name, String content) {
        try {
            Files.write(tempDir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("测试前置写入失败", e);
        }
    }
}
