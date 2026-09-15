package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolPaths} 的单元测试。
 * <p>
 * 重点锁住展示口径：工作目录内显示相对路径（模型才能把两次调用的输出对上），
 * 目录外退回绝对路径（否则无法定位）。
 *
 * @author zcd
 */
@DisplayName("ToolPaths 路径约定")
class ToolPathsTest {

    /** 进程工作目录，与实现保持同一口径。 */
    private static final Path WORKING_DIRECTORY = Paths.get("").toAbsolutePath().normalize();

    @Test
    @DisplayName("相对路径应按工作目录解析成绝对路径")
    void resolve_should_resolveAgainstWorkingDirectory() {
        assertEquals(WORKING_DIRECTORY.resolve("a/b.txt"), ToolPaths.resolve("a/b.txt"));
    }

    @Test
    @DisplayName("路径应做规范化：多余的 . 与 .. 被消掉")
    void resolve_should_normalize() {
        assertEquals(WORKING_DIRECTORY.resolve("b.txt"), ToolPaths.resolve("./a/../b.txt"));
    }

    @Test
    @DisplayName("工作目录内的路径应显示为相对路径")
    void display_should_returnRelativePath_when_insideWorkingDirectory() {
        assertEquals("src/Main.java", ToolPaths.display(WORKING_DIRECTORY.resolve("src/Main.java")));
    }

    @Test
    @DisplayName("工作目录自身应显示为 .")
    void display_should_returnDot_when_pathIsWorkingDirectory() {
        assertEquals(".", ToolPaths.display(WORKING_DIRECTORY));
    }

    @Test
    @DisplayName("工作目录外的路径应显示为绝对路径")
    void display_should_returnAbsolutePath_when_outsideWorkingDirectory() {
        Path outside = WORKING_DIRECTORY.getParent().resolve("elsewhere.txt");

        String display = ToolPaths.display(outside);

        assertTrue(Paths.get(display).isAbsolute(), display);
    }
}
