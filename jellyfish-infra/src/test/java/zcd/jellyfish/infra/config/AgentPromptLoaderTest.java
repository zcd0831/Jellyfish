package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentPromptLoader} 的单元测试：验证文件名安全校验、md 读取与基准目录推导。
 *
 * @author zcd
 */
class AgentPromptLoaderTest {

    /** 临时目录，用于构造真实的本地提示词文件。 */
    @TempDir
    Path tempDir;

    /** 被测加载器，走真实 {@link SettingsReader}。 */
    private final AgentPromptLoader loader = new AgentPromptLoader(new SettingsReader());

    @Test
    void constructor_should_reject_null_reader() {
        // When / Then
        assertThrows(NullPointerException.class, () -> new AgentPromptLoader(null));
    }

    @Test
    void isSafeAgentId_should_accept_plain_and_cjk_ids() {
        // When / Then：字符集本身不受限，中文 agent 名同样可用
        assertTrue(loader.isSafeAgentId("coder"));
        assertTrue(loader.isSafeAgentId("code-reviewer_v2"));
        assertTrue(loader.isSafeAgentId("编码助手"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "../evil", "a/b", "a\\b", ".hidden", "a..b", "a:b", "a?b", "a*b", "a|b"})
    void isSafeAgentId_should_reject_unsafe_ids(String agentId) {
        // When / Then：含路径分隔符、`..` 或 Windows 保留字符的标识不能当文件名
        assertFalse(loader.isSafeAgentId(agentId));
    }

    @Test
    void load_should_read_md_from_local_directory() throws IOException {
        // Given
        Files.write(tempDir.resolve("coder.md"), "你是编码助手".getBytes(StandardCharsets.UTF_8));

        // When
        String prompt = loader.load(tempDir.toString(), "coder");

        // Then
        assertEquals("你是编码助手", prompt);
    }

    @Test
    void load_should_return_null_when_md_missing() {
        // When / Then
        assertNull(loader.load(tempDir.toString(), "ghost"));
    }

    @Test
    void load_should_return_null_when_base_blank() {
        // When / Then
        assertNull(loader.load(null, "coder"));
        assertNull(loader.load("  ", "coder"));
    }

    @Test
    void load_should_return_null_when_agent_id_unsafe() {
        // When / Then：非法标识连路径都不该被拼出来
        assertNull(loader.load(tempDir.toString(), "../evil"));
    }

    @Test
    void load_should_resolve_from_classpath_root() {
        // When / Then：classpath 根基准拼成 classpath:xxx.md；测试 classpath 上没有该资源
        assertNull(loader.load(AgentPromptLoader.CLASSPATH_ROOT, "no-such-agent-xyz"));
    }

    @Test
    void promptBaseOf_should_return_parent_directory_for_local_path() {
        // When
        String base = loader.promptBaseOf(tempDir.resolve("agents.json").toString());

        // Then
        assertEquals(tempDir.toString(), base);
    }

    @Test
    void promptBaseOf_should_return_classpath_root_for_classpath_path() {
        // When / Then：内置资源与配置文件同在包根
        assertEquals(AgentPromptLoader.CLASSPATH_ROOT, loader.promptBaseOf("classpath:default-agent.json"));
    }

    @Test
    void promptBaseOf_should_return_null_when_no_parent() {
        // When / Then：单层相对路径没有父目录，无从定位提示词
        assertNull(loader.promptBaseOf("agents.json"));
    }

    @Test
    void promptBaseOf_should_return_null_when_path_blank() {
        // When / Then
        assertNull(loader.promptBaseOf(null));
        assertNull(loader.promptBaseOf("   "));
    }
}
