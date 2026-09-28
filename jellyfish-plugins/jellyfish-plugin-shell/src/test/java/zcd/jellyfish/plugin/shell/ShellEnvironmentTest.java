package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellEnvironment} 的单元测试。
 * <p>
 * 关注点：脱敏是否默认生效、防挂死变量是否注入、以及用户显式配置能不能盖住默认值。
 *
 * @author zcd
 */
@DisplayName("ShellEnvironment")
class ShellEnvironmentTest {

    @Test
    @DisplayName("默认剔除名字里带凭据关键词的环境变量")
    void build_should_drop_sensitive_variables() {
        Map<String, String> parent = new HashMap<String, String>();
        parent.put("PATH", "/usr/bin");
        parent.put("AWS_SECRET_ACCESS_KEY", "泄漏");
        parent.put("GITHUB_TOKEN", "泄漏");
        parent.put("DB_PASSWORD", "泄漏");
        parent.put("MY_CREDENTIAL", "泄漏");
        parent.put("SOME_KEY", "泄漏");

        Map<String, String> environment = ShellEnvironment.build(parent,
                ShellEnvironment.sensitivePatterns(null), null);

        assertEquals("/usr/bin", environment.get("PATH"));
        for (String name : Arrays.asList("AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "DB_PASSWORD",
                "MY_CREDENTIAL", "SOME_KEY")) {
            assertNull(environment.get(name), name + " 不该被传给子进程");
        }
    }

    @Test
    @DisplayName("默认注入防挂死变量——否则 git log 会停在分页器里等按键")
    void build_should_inject_anti_hang_variables() {
        Map<String, String> environment = ShellEnvironment.build(null, null, null);

        assertEquals("cat", environment.get("PAGER"));
        assertEquals("cat", environment.get("GIT_PAGER"));
        assertEquals("0", environment.get("GIT_TERMINAL_PROMPT"));
        assertEquals("dumb", environment.get("TERM"));
    }

    @Test
    @DisplayName("用户配置盖住默认值——「可配置」的含义就是我说了算")
    void build_should_let_user_overrides_win() {
        Map<String, String> overrides = new HashMap<String, String>();
        overrides.put("TERM", "xterm-256color");
        overrides.put("CUSTOM", "1");

        Map<String, String> environment = ShellEnvironment.build(null, null, overrides);

        assertEquals("xterm-256color", environment.get("TERM"));
        assertEquals("1", environment.get("CUSTOM"));
        assertEquals("cat", environment.get("PAGER"));
    }

    @Test
    @DisplayName("用户追加的脱敏模式叠加在内置表之上")
    void sensitivePatterns_should_extend_defaults() {
        List<String> patterns = ShellEnvironment.sensitivePatterns(Arrays.asList("*INTERNAL*"));

        Map<String, String> parent = new HashMap<String, String>();
        parent.put("INTERNAL_HOST", "x");
        parent.put("API_KEY", "y");

        Map<String, String> environment = ShellEnvironment.build(parent, patterns, null);

        assertNull(environment.get("INTERNAL_HOST"));
        assertNull(environment.get("API_KEY"));
    }

    @ParameterizedTest
    @DisplayName("通配匹配大小写不敏感，且支持 ? ")
    @ValueSource(strings = {"*KEY*", "*key*", "AWS_*", "A?S_KEY"})
    void matches_should_be_caseInsensitive(String pattern) {
        assertTrue(ShellEnvironment.matches(pattern, "AWS_KEY"));
    }

    @Test
    @DisplayName("通配模式不匹配无关名字")
    void matches_should_not_match_unrelated() {
        assertFalse(ShellEnvironment.matches("*TOKEN*", "PATH"));
        assertFalse(ShellEnvironment.matches("API_KEY", "API_KEY_SUFFIX_MISMATCH_X"));
    }

    @Test
    @DisplayName("空环境也能构造，不抛异常")
    void build_should_tolerate_empty_input() {
        assertFalse(ShellEnvironment.build(null, null, null).isEmpty());
        assertFalse(ShellEnvironment.build(new HashMap<String, String>(),
                ShellEnvironment.sensitivePatterns(null), null).isEmpty());
    }
}
