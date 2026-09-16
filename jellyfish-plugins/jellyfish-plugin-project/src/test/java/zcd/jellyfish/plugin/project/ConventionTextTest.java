package zcd.jellyfish.plugin.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ConventionText} 的单元测试：大小文案的分档与未知大小。
 *
 * @author zcd
 */
@DisplayName("项目约定文案")
class ConventionTextTest {

    @Test
    @DisplayName("小于 1 KB 按字节显示")
    void size_should_useBytes_when_small() {
        assertEquals("（约 1023 字节）", ConventionText.size(new ConventionFile("AGENTS.md", 1023L)));
    }

    @Test
    @DisplayName("1 KB 及以上按 KB 显示")
    void size_should_useKilobytes_when_larger() {
        assertEquals("（约 1 KB）", ConventionText.size(new ConventionFile("AGENTS.md", 1024L)));
        assertEquals("（约 89 KB）", ConventionText.size(new ConventionFile("AGENTS.md", 91326L)));
    }

    @Test
    @DisplayName("1 MB 及以上按 MB 显示")
    void size_should_useMegabytes_when_huge() {
        assertEquals("（约 1 MB）",
                ConventionText.size(new ConventionFile("AGENTS.md", 1024L * 1024L)));
    }

    @Test
    @DisplayName("大小未知时不编造数字")
    void size_should_sayUnknown_when_sizeIsUnknown() {
        assertEquals("（大小未知）", ConventionText.size(new ConventionFile("AGENTS.md", -1L)));
    }
}
