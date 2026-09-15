package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BuiltinAgentLoader} 的单元测试：验证定义与 md 的拼接、缓存，
 * 以及「内置资源缺失属打包错误、必须直接抛错」这条不对称的失败语义。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class BuiltinAgentLoaderTest {

    /** 配置读取门面。 */
    @Mock
    private ConfigLoader configLoader;

    /** 提示词加载器。 */
    @Mock
    private AgentPromptLoader promptLoader;

    @Test
    void load_should_read_definition_and_attach_md_prompt() {
        // Given
        AgentDefinition definition = new AgentDefinition("jellyfish", "系统默认 agent", null);
        when(configLoader.read(BuiltinAgentLoader.DEFAULT_AGENT_PATH, AgentDefinition.class)).thenReturn(definition);
        when(promptLoader.isSafeAgentId("jellyfish")).thenReturn(true);
        when(promptLoader.load(AgentPromptLoader.CLASSPATH_ROOT, "jellyfish")).thenReturn("你是 Jellyfish");

        // When
        AgentDefinition loaded = newLoader().load();

        // Then
        assertEquals("jellyfish", loaded.getAgentId());
        assertEquals("系统默认 agent", loaded.getDescription());
        assertEquals("你是 Jellyfish", loaded.getSystemPrompt());
    }

    @Test
    void load_should_throw_when_definition_missing() {
        // Given：内置 JSON 缺失是打包错误，继续启动只会得到一个静默退化的内核
        when(configLoader.read(BuiltinAgentLoader.DEFAULT_AGENT_PATH, AgentDefinition.class)).thenReturn(null);

        // When / Then
        assertThrows(JellyfishException.class, () -> newLoader().load());
    }

    @Test
    void load_should_throw_when_agent_id_unsafe() {
        // Given
        when(configLoader.read(BuiltinAgentLoader.DEFAULT_AGENT_PATH, AgentDefinition.class))
                .thenReturn(new AgentDefinition("../evil", null, null));
        when(promptLoader.isSafeAgentId("../evil")).thenReturn(false);

        // When / Then
        assertThrows(JellyfishException.class, () -> newLoader().load());
    }

    @Test
    void load_should_throw_when_prompt_missing() {
        // Given：系统 agent 没有提示词等于回到「无身份裸奔」，同样按错误处理
        when(configLoader.read(BuiltinAgentLoader.DEFAULT_AGENT_PATH, AgentDefinition.class))
                .thenReturn(new AgentDefinition("jellyfish", null, null));
        when(promptLoader.isSafeAgentId("jellyfish")).thenReturn(true);
        when(promptLoader.load(AgentPromptLoader.CLASSPATH_ROOT, "jellyfish")).thenReturn(null);

        // When / Then
        assertThrows(JellyfishException.class, () -> newLoader().load());
    }

    @Test
    void load_should_cache_result_across_calls() {
        // Given
        when(configLoader.read(BuiltinAgentLoader.DEFAULT_AGENT_PATH, AgentDefinition.class))
                .thenReturn(new AgentDefinition("jellyfish", null, null));
        when(promptLoader.isSafeAgentId("jellyfish")).thenReturn(true);
        when(promptLoader.load(AgentPromptLoader.CLASSPATH_ROOT, "jellyfish")).thenReturn("prompt");
        BuiltinAgentLoader loader = newLoader();

        // When
        AgentDefinition first = loader.load();
        AgentDefinition second = loader.load();

        // Then：内置资源不参与热更新，只读一次
        assertSame(first, second);
        verify(configLoader, times(1)).read(BuiltinAgentLoader.DEFAULT_AGENT_PATH, AgentDefinition.class);
    }

    @Test
    void constructor_should_reject_null_collaborators() {
        // When / Then
        assertThrows(NullPointerException.class, () -> new BuiltinAgentLoader(null, promptLoader));
        assertThrows(NullPointerException.class, () -> new BuiltinAgentLoader(configLoader, null));
    }

    /**
     * 构造被测实例。
     *
     * @return BuiltinAgentLoader 实例
     */
    private BuiltinAgentLoader newLoader() {
        return new BuiltinAgentLoader(configLoader, promptLoader);
    }
}
