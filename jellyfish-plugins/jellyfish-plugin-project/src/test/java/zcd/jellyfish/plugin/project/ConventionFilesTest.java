package zcd.jellyfish.plugin.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ConventionFiles} 的单元测试：命中口径与查找基准。
 * <p>
 * 每个用例都注入临时目录作为基准，因此不依赖测试进程真实的工作目录——
 * 唯一例外是那条专门锁「基准就是进程工作目录」的用例。
 *
 * @author zcd
 */
@DisplayName("项目约定文件探测")
class ConventionFilesTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    @Test
    @DisplayName("约定文件存在且非空时应返回相对路径")
    void presentName_should_returnName_whenFileExists() throws IOException {
        Files.write(directory.resolve(ConventionFiles.CONVENTION_FILE), "# 约定".getBytes("UTF-8"));

        assertEquals("AGENTS.md", new ConventionFiles(directory).presentName());
    }

    @Test
    @DisplayName("约定文件不存在时应返回 null")
    void presentName_should_returnNull_whenFileMissing() {
        assertNull(new ConventionFiles(directory).presentName());
    }

    @Test
    @DisplayName("空文件视为不存在：指向它只会白费一次工具调用")
    void presentName_should_returnNull_whenFileIsEmpty() throws IOException {
        Files.createFile(directory.resolve(ConventionFiles.CONVENTION_FILE));

        assertNull(new ConventionFiles(directory).presentName());
    }

    @Test
    @DisplayName("同名目录不算命中：约定文件必须是常规文件")
    void presentName_should_returnNull_whenNameIsDirectory() throws IOException {
        Files.createDirectory(directory.resolve(ConventionFiles.CONVENTION_FILE));

        assertNull(new ConventionFiles(directory).presentName());
    }

    @Test
    @DisplayName("查找基准应与工具的相对路径基准同一处：进程工作目录")
    void ofWorkingDirectory_should_useProcessWorkingDirectory() {
        Path expected = Paths.get("").toAbsolutePath().normalize();

        assertEquals(expected, ConventionFiles.ofWorkingDirectory().baseDirectory());
    }
}
