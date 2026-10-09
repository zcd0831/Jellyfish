package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishSettings} 的单元测试：验证各段缺省、反序列化，以及「这一段写没写」的声明标记。
 * <p>
 * 声明标记是双源合并「没写就回退全局级」的唯一判据，因此它必须与「内容等于缺省值」分开断言：
 * 「没写」与「写了、内容恰好是缺省值」在合并里的后果完全不同。
 *
 * @author zcd
 */
class JellyfishSettingsTest {

    @Test
    void getPlugins_should_return_empty_object_when_null() {
        // When
        JellyfishSettings settings = new JellyfishSettings(null, null, null);

        // Then
        assertTrue(settings.getPlugins().isEmpty());
        assertTrue(settings.getReact().isDefault());
        assertTrue(settings.getPermission().isDefault());
        assertTrue(settings.isEmpty());
    }

    @Test
    void getPlugins_should_return_same_instance_when_given() {
        // Given
        PluginsSettings plugins = new PluginsSettings(Collections.singletonList("plugin-a"), null, null);

        // When
        JellyfishSettings settings = new JellyfishSettings(plugins, null, null);

        // Then
        assertSame(plugins, settings.getPlugins());
    }

    @Test
    void getReact_should_return_same_instance_when_given() {
        // Given
        ReactSettings react = new ReactSettings(3, 0, 100, null, null, null);

        // When
        JellyfishSettings settings = new JellyfishSettings(null, react, null);

        // Then
        assertSame(react, settings.getReact());
        // 显式配置（含显式 0 预留）不算「未配置」
        assertTrue(!settings.isEmpty());
    }

    @Test
    void deserialization_should_bind_plugins_section() {
        // Given
        String json = "{\"plugins\":{\"disabled\":[\"plugin-b\"],"
                + "\"configurations\":{\"plugin-a\":{\"readOnlyTools\":[\"read_file\"]}}}}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertEquals(Collections.singletonList("plugin-b"), settings.getPlugins().getDisabled());
        assertEquals(Collections.singletonList("read_file"),
                settings.getPlugins().getConfigurations().get("plugin-a").get("readOnlyTools"));
    }

    @Test
    void deserialization_should_bind_react_section() {
        // Given
        String json = "{\"react\":{\"maxRounds\":5,\"contextReserveTokens\":0,\"maxToolOutputChars\":100}}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertEquals(5, settings.getReact().getMaxRounds());
        assertEquals(0, settings.getReact().getContextReserveTokens());
        assertEquals(100, settings.getReact().getMaxToolOutputChars());
    }

    @Test
    void getPermission_should_return_same_instance_when_given() {
        // Given
        PermissionApprovalSettings permission = new PermissionApprovalSettings(30);

        // When
        JellyfishSettings settings = new JellyfishSettings(null, null, permission);

        // Then
        assertSame(permission, settings.getPermission());
        assertTrue(!settings.isEmpty());
    }

    @Test
    void deserialization_should_bind_permission_section() {
        // Given
        String json = "{\"permission\":{\"approvalTimeoutSeconds\":30}}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertEquals(30, settings.getPermission().getApprovalTimeoutSeconds());
    }

    @Test
    void getSubAgent_should_return_default_when_null() {
        // When
        JellyfishSettings settings = new JellyfishSettings(null, null, null);

        // Then
        assertTrue(settings.getSubAgent().isDefault());
    }

    @Test
    void getSubAgent_should_return_same_instance_when_given() {
        // Given
        SubAgentSettings subAgent = new SubAgentSettings(false, null, null, null, null, null, null, null);

        // When
        JellyfishSettings settings = new JellyfishSettings(null, null, null, subAgent);

        // Then
        assertSame(subAgent, settings.getSubAgent());
        assertTrue(!settings.isEmpty());
    }

    @Test
    void deserialization_should_bind_sub_agent_section() {
        // Given
        String json = "{\"subAgent\":{\"enabled\":false,\"maxDepth\":1}}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertTrue(!settings.getSubAgent().isEnabled());
        assertEquals(1, settings.getSubAgent().getMaxDepth());
    }

    @Test
    void deserialization_should_ignore_unknown_sections() {
        // Given：模型段已迁到 models.json，jellyfish.json 里出现它属于历史残留，应被忽略而不是报错
        String json = "{\"defaultProvider\":\"openai\",\"plugins\":{\"disabled\":[\"plugin-b\"]}}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertEquals(Collections.singletonList("plugin-b"), settings.getPlugins().getDisabled());
    }

    @Test
    void deserialization_should_tolerate_empty_object() {
        // Given
        String json = "{}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertTrue(settings.isEmpty());
    }

    @Test
    void deserialization_should_report_no_section_as_declared_for_empty_object() {
        // When：一份什么都没写的文件，五个段都不算「声明过」
        JellyfishSettings settings = ObjectMapperWrapper.readValue("{}", JellyfishSettings.class);

        // Then：这是双源合并「没写就回退全局级」的唯一判据，漏一个就会把全局级那一段顶成缺省
        assertFalse(settings.isPluginsDeclared());
        assertFalse(settings.isReactDeclared());
        assertFalse(settings.isPermissionDeclared());
        assertFalse(settings.isSubAgentDeclared());
        assertFalse(settings.isAskDeclared());
    }

    @Test
    void deserialization_should_report_only_written_sections_as_declared() {
        // Given：只写了 ask 段（且其内容恰好等于缺省值，仍必须算「声明过」）
        String json = "{\"ask\":{\"timeoutSeconds\":" + AskSettings.DEFAULT_TIMEOUT_SECONDS + "}}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertTrue(settings.isAskDeclared(), "写了这一段就得算声明，哪怕内容与缺省值一致");
        assertFalse(settings.isPluginsDeclared());
        assertFalse(settings.isReactDeclared());
        assertFalse(settings.isPermissionDeclared());
        assertFalse(settings.isSubAgentDeclared());
        assertTrue(settings.isEmpty(), "内容全为缺省值时仍然算「什么都没配」");
    }

    @Test
    void constructor_should_report_written_sections_as_declared_when_given() {
        // Given / When：非反序列化的构造点（合并产物、测试）传进来的段都算「写过」
        JellyfishSettings settings = new JellyfishSettings(new PluginsSettings(null, null, null),
                new ReactSettings(), new PermissionApprovalSettings(), new SubAgentSettings(), new AskSettings());

        // Then
        assertTrue(settings.isPluginsDeclared());
        assertTrue(settings.isReactDeclared());
        assertTrue(settings.isPermissionDeclared());
        assertTrue(settings.isSubAgentDeclared());
        assertTrue(settings.isAskDeclared());
    }
}
