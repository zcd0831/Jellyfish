package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置资源的「打包体检」：用<b>真实</b>的加载链路读<b>真实</b>的资源文件。
 * <p>
 * <b>为什么必须单独一条</b>：{@link BuiltinAgentLoaderTest} 为了覆盖缺失 / 非法等分支，把
 * {@code ConfigLoader} 与 {@code AgentPromptLoader} 全 mock 了——于是「文件到底在不在 jar 里」这件事
 * 没有任何测试盯着。而它的失败方式是「打包错了、启动时才炸」。
 * <p>
 * 资源<b>放在本模块</b>（{@code jellyfish-infra/src/main/resources}）而不是外壳模块，正是为了让这条
 * 测试成立：读它们的 {@code BuiltinAgentLoader} 就在 infra，资源跟着读者走，谁读谁带。
 *
 * @author zcd
 */
@DisplayName("内置资源打包体检")
class BuiltinAgentResourcesTest {

    @Test
    @DisplayName("内置默认 agent 的定义与提示词都要真的在 classpath 上，且拼得起来")
    void load_should_readRealBuiltinResources() {
        SettingsReader settingsReader = new SettingsReader();
        BuiltinAgentLoader loader = new BuiltinAgentLoader(
                new ConfigLoader(settingsReader, new SettingsBinder()),
                new AgentPromptLoader(settingsReader));

        AgentDefinition definition = loader.load();

        assertEquals("jellyfish", definition.getAgentId());
        assertTrue(definition.getSystemPrompt().length() > 50, "提示词不该是空壳");
        assertFalse(definition.getSystemPrompt().trim().isEmpty());
    }

    @Test
    @DisplayName("内置提示词文件非空：空文件会让 agent 静默失去人格设定")
    void promptFile_should_beNonBlank() {
        String prompt = new SettingsReader().read(AgentPromptLoader.CLASSPATH_ROOT + "jellyfish.md");

        assertFalse(prompt == null || prompt.trim().isEmpty(), "内置提示词文件缺失或为空");
        assertTrue(prompt.contains("Jellyfish"), prompt);
    }
}
