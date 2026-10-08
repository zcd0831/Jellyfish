package zcd.jellyfish.infra.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link HomePaths} 的单元测试：验证 {@code ~} 展开的边界。
 *
 * @author zcd
 */
class HomePathsTest {

    /** 临时目录，用作 {@code user.home}。 */
    @TempDir
    Path tempDir;

    /** 原始 {@code user.home}，测试结束后还原。 */
    private String originalHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (originalHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void expand_should_replace_bare_tilde_when_path_is_only_tilde() {
        assertEquals(tempDir.toString(), HomePaths.expand("~"));
    }

    @Test
    void expand_should_replace_leading_tilde_when_followed_by_separator() {
        assertEquals(tempDir + "/plugins", HomePaths.expand("~/plugins"));
    }

    @Test
    void expand_should_keep_path_when_no_tilde() {
        assertEquals("/opt/jellyfish/plugins", HomePaths.expand("/opt/jellyfish/plugins"));
        assertEquals("plugins", HomePaths.expand("plugins"));
    }

    @Test
    void expand_should_keep_path_when_tilde_names_another_user() {
        assertEquals("~other/plugins", HomePaths.expand("~other/plugins"));
    }

    @Test
    void expand_should_return_null_when_path_null() {
        assertNull(HomePaths.expand(null));
    }

    @Test
    void abbreviate_should_replaceHomePrefix_when_pathIsUnderHome() {
        assertEquals("~/plugins/x.json", HomePaths.abbreviate(tempDir.resolve("plugins/x.json")));
    }

    @Test
    void abbreviate_should_returnTilde_when_pathIsHomeItself() {
        assertEquals("~", HomePaths.abbreviate(tempDir));
    }

    @Test
    void abbreviate_should_keepAbsolutePath_when_pathIsOutsideHome() {
        assertEquals("/opt/jellyfish/x", HomePaths.abbreviate(Paths.get("/opt/jellyfish/x")));
    }

    @Test
    void abbreviate_should_beInverseOfExpand() {
        // 一对互逆变换：回灌的 `~` 形式展开后必须回到原来那个真实文件
        Path file = tempDir.resolve("plugins/x.json");
        assertEquals(file, Paths.get(HomePaths.expand(HomePaths.abbreviate(file))));
    }

    @Test
    void abbreviate_should_returnNull_when_pathNull() {
        assertNull(HomePaths.abbreviate(null));
    }
}
