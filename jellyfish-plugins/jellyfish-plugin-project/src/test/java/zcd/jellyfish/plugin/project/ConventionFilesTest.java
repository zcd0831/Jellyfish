package zcd.jellyfish.plugin.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConventionFiles} 的单元测试：命中口径、查找基准与带上限的读取。
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
    @DisplayName("约定文件存在且非空时应返回名称与大小")
    void probe_should_returnNameAndSize_whenFileExists() throws IOException {
        writeConventionFile("# 约定");

        ConventionFile file = new ConventionFiles(directory).probe();

        assertNotNull(file);
        assertEquals("AGENTS.md", file.name());
        assertEquals(Files.size(directory.resolve(ConventionFiles.CONVENTION_FILE)), file.sizeInBytes());
    }

    @Test
    @DisplayName("约定文件不存在时应返回 null")
    void probe_should_returnNull_whenFileMissing() {
        assertNull(new ConventionFiles(directory).probe());
    }

    @Test
    @DisplayName("空文件视为不存在：指向它只会白费一次工具调用")
    void probe_should_returnNull_whenFileIsEmpty() throws IOException {
        Files.createFile(directory.resolve(ConventionFiles.CONVENTION_FILE));

        assertNull(new ConventionFiles(directory).probe());
    }

    @Test
    @DisplayName("同名目录不算命中：约定文件必须是常规文件")
    void probe_should_returnNull_whenNameIsDirectory() throws IOException {
        Files.createDirectory(directory.resolve(ConventionFiles.CONVENTION_FILE));

        assertNull(new ConventionFiles(directory).probe());
    }

    @Test
    @DisplayName("查找基准应与工具的相对路径基准同一处：进程工作目录")
    void ofWorkingDirectory_should_useProcessWorkingDirectory() {
        Path expected = Paths.get("").toAbsolutePath().normalize();

        assertEquals(expected, ConventionFiles.ofWorkingDirectory().baseDirectory());
    }

    @Test
    @DisplayName("装得下上限时应读出全文且不标记截断")
    void read_should_returnWholeText_whenFitsWithinLimit() throws IOException {
        writeConventionFile("SENTINEL-BODY");

        ConventionFiles.Reading reading = readWithLimit(1024);

        assertNotNull(reading);
        assertEquals("SENTINEL-BODY", reading.text());
        assertFalse(reading.truncated());
    }

    @Test
    @DisplayName("上限恰好等于文件大小时应读出全文：边界不该被判成截断")
    void read_should_returnWholeText_whenLimitEqualsSize() throws IOException {
        writeConventionFile("1234567890");

        ConventionFiles.Reading reading = readWithLimit(10);

        assertNotNull(reading);
        assertEquals("1234567890", reading.text());
        assertFalse(reading.truncated());
    }

    @Test
    @DisplayName("探测之后文件变长时应截断并如实标记：静默截断会让模型拿残缺内容当完整过往")
    void read_should_markTruncated_whenFileGrewAfterProbe() throws IOException {
        writeConventionFile("12345");
        ConventionFile probed = new ConventionFiles(directory).probe();
        writeConventionFile("1234567890");

        ConventionFiles.Reading reading = new ConventionFiles(directory).read(probed, 5);

        assertNotNull(reading);
        assertEquals("12345", reading.text());
        assertTrue(reading.truncated());
    }

    @Test
    @DisplayName("读取失败时应返回 null，让上层退回路径指引")
    void read_should_returnNull_whenFileUnreadable() throws IOException {
        Files.createDirectory(directory.resolve(ConventionFiles.CONVENTION_FILE));

        assertNull(new ConventionFiles(directory).read(new ConventionFile("AGENTS.md", 8L), 1024));
    }

    @Test
    @DisplayName("大小已知且不超过上限才算装得下：大小未知一律不算")
    void fitsWithin_should_rejectUnknownSize() {
        assertTrue(new ConventionFile("AGENTS.md", 10L).fitsWithin(10));
        assertFalse(new ConventionFile("AGENTS.md", 11L).fitsWithin(10));
        assertFalse(new ConventionFile("AGENTS.md", -1L).fitsWithin(Integer.MAX_VALUE));
    }

    /**
     * 以给定上限读取临时目录里的约定文件。
     *
     * @param limit 读取上限
     * @return 读取结果
     */
    private ConventionFiles.Reading readWithLimit(int limit) {
        ConventionFiles files = new ConventionFiles(directory);
        return files.read(files.probe(), limit);
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
