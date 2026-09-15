package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeConfig} 的单元测试：验证双源读取、合并规则、快照读取与参数校验。
 * <p>
 * 配置不再由构造器加载，因此每个用例在构造后显式调用 {@link RuntimeConfig#refresh()}；
 * 通过 {@link TempDir} 构造真实的全局级/项目级文件，仅 mock 提供路径的 {@link AppConfig}。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class RuntimeConfigTest {

    /** 临时目录，用于构造真实的本地配置文件。 */
    @TempDir
    Path tempDir;

    /** 只 mock 提供双源路径的应用级配置。 */
    @Mock
    AppConfig appConfig;

    @Test
    void refresh_should_merge_global_and_project_when_both_exist() throws IOException {
        // Given
        Path global = writeFile("global.json",
                "{\"defaultProvider\":\"global-provider\",\"defaultModel\":\"global-model\","
                        + "\"providers\":{"
                        + "\"openai\":{\"type\":\"openai\",\"baseUrl\":\"https://global\"},"
                        + "\"azure\":{\"type\":\"azure\"}}}");
        Path project = writeFile("project.json",
                "{\"defaultProvider\":\"project-provider\","
                        + "\"providers\":{"
                        + "\"openai\":{\"type\":\"openai\",\"baseUrl\":\"https://project\"},"
                        + "\"ollama\":{\"type\":\"ollama\"}}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(global, project));

        // Then：默认值项目级覆盖、缺省项回退全局级
        assertEquals("project-provider", runtimeConfig.getDefaultProvider());
        assertEquals("global-model", runtimeConfig.getDefaultModel());
        // Then：同名 provider 整对象替换，顺序保留全局级位置，新 provider 追加在后
        assertEquals(Arrays.asList("openai", "azure", "ollama"), providerNames(runtimeConfig.getProviders()));
        assertEquals("https://project", runtimeConfig.getProviders().get(0).getBaseUrl());
    }

    @Test
    void refresh_should_backfill_provider_name_from_map_key() throws IOException {
        // Given
        Path global = writeFile("global.json",
                "{\"providers\":{\"my-provider\":{\"type\":\"openai\"}}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(global, null));

        // Then
        assertEquals("my-provider", runtimeConfig.getProviders().get(0).getName());
    }

    @Test
    void refresh_should_fall_back_to_global_when_project_value_is_blank() throws IOException {
        // Given
        Path global = writeFile("global.json",
                "{\"defaultProvider\":\"global-provider\",\"defaultModel\":\"global-model\"}");
        Path project = writeFile("project.json",
                "{\"defaultProvider\":\"\",\"defaultModel\":\"project-model\"}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(global, project));

        // Then
        assertEquals("global-provider", runtimeConfig.getDefaultProvider());
        assertEquals("project-model", runtimeConfig.getDefaultModel());
    }

    @Test
    void refresh_should_return_empty_providers_when_files_missing() {
        // Given
        ConfigPaths paths = pathsTo(tempDir.resolve("missing-global.json"), tempDir.resolve("missing-project.json"));

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(paths);

        // Then
        assertNotNull(runtimeConfig.getModelSettings());
        assertTrue(runtimeConfig.getProviders().isEmpty());
        assertNull(runtimeConfig.getDefaultProvider());
        assertNull(runtimeConfig.getDefaultModel());
    }

    @Test
    void refresh_should_skip_null_provider_when_map_value_is_null() throws IOException {
        // Given
        Path global = writeFile("global.json", "{\"providers\":{\"openai\":null}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(global, null));

        // Then
        assertTrue(runtimeConfig.getProviders().isEmpty());
    }

    @Test
    void getProviders_should_return_unmodifiable_list() throws IOException {
        // Given
        Path global = writeFile("global.json", "{\"providers\":{\"openai\":{\"type\":\"openai\"}}}");
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(global, null));

        // When / Then
        List<Provider> providers = runtimeConfig.getProviders();
        Provider added = new Provider("other", "openai", null, null, null);
        assertThrows(UnsupportedOperationException.class, () -> providers.add(added));
    }

    @Test
    void refresh_should_reload_when_file_changed() throws IOException {
        // Given
        Path global = writeFile("global.json", "{\"providers\":{\"first\":{\"type\":\"openai\"}}}");
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(global, null));
        assertEquals(Arrays.asList("first"), providerNames(runtimeConfig.getProviders()));

        // When
        Files.write(global, "{\"providers\":{\"second\":{\"type\":\"openai\"}}}".getBytes(StandardCharsets.UTF_8));
        runtimeConfig.refresh();

        // Then
        assertEquals(Arrays.asList("second"), providerNames(runtimeConfig.getProviders()));
    }

    @Test
    void load_should_pass_null_for_missing_side_and_project_for_existing() throws IOException {
        // Given
        Path project = writeFile("project.json", "{\"defaultProvider\":\"project-provider\"}");
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, project));
        AtomicReference<ModelSettings> globalRef = new AtomicReference<>();
        AtomicReference<ModelSettings> projectRef = new AtomicReference<>();

        // When
        ModelSettings merged = runtimeConfig.load(pathsTo(null, project), ModelSettings.class, (global, proj) -> {
            globalRef.set(global);
            projectRef.set(proj);
            return new ModelSettings(null, null, null);
        });

        // Then
        assertNotNull(merged);
        assertNull(globalRef.get());
        assertNotNull(projectRef.get());
        assertEquals("project-provider", projectRef.get().getDefaultProvider());
    }

    @Test
    void load_should_pass_null_for_both_sides_when_paths_is_null() {
        // Given
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null));
        AtomicReference<ModelSettings> globalRef = new AtomicReference<>();
        AtomicReference<ModelSettings> projectRef = new AtomicReference<>();

        // When
        ModelSettings merged = runtimeConfig.load(null, ModelSettings.class, (global, project) -> {
            globalRef.set(global);
            projectRef.set(project);
            return new ModelSettings(null, null, null);
        });

        // Then
        assertNotNull(merged);
        assertNull(globalRef.get());
        assertNull(projectRef.get());
    }

    @Test
    void load_should_return_merger_result_when_paths_are_blank() {
        // Given
        ConfigPaths blankPaths = new ConfigPaths();
        RuntimeConfig runtimeConfig = newRuntimeConfig(blankPaths);
        ModelSettings expected = new ModelSettings(null, null, null);

        // When
        ModelSettings merged = runtimeConfig.load(blankPaths, ModelSettings.class, (global, project) -> expected);

        // Then
        assertEquals(expected, merged);
    }

    @Test
    void load_should_read_once_when_global_and_project_paths_are_same() throws IOException {
        // Given
        Path shared = writeFile("shared.json", "{\"defaultProvider\":\"provider\"}");
        ConfigPaths paths = pathsTo(shared, shared);
        when(appConfig.getModel()).thenReturn(paths);
        ConfigLoader configLoader = mock(ConfigLoader.class);
        when(configLoader.read(shared.toString(), ModelSettings.class))
                .thenReturn(new ModelSettings(null, null, null));

        // When
        RuntimeConfig runtimeConfig = new RuntimeConfig(appConfig, configLoader, new RecordingPublisher());
        runtimeConfig.refresh();

        // Then：同一路径只读取一次
        verify(configLoader, times(1)).read(shared.toString(), ModelSettings.class);
    }

    @Test
    void refresh_should_publish_warning_when_file_missing() {
        // Given
        Path missing = tempDir.resolve("missing.json");
        ConfigPaths paths = pathsTo(missing, null);
        when(appConfig.getModel()).thenReturn(paths);
        RecordingPublisher publisher = new RecordingPublisher();

        // When
        RuntimeConfig runtimeConfig = new RuntimeConfig(appConfig,
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
        runtimeConfig.refresh();

        // Then
        assertTrue(publisher.warnings().stream().anyMatch(warning -> missing.toString().equals(warning.getSource())));
    }

    @Test
    void load_should_throw_when_merger_returns_null() {
        // Given
        ConfigPaths paths = pathsTo(null, null);
        RuntimeConfig runtimeConfig = newRuntimeConfig(paths);

        // When / Then
        assertThrows(JellyfishException.class,
                () -> runtimeConfig.load(paths, ModelSettings.class, RuntimeConfigTest::noMerge));
    }

    @Test
    void load_should_throw_when_type_is_null() {
        // Given
        ConfigPaths paths = pathsTo(null, null);
        RuntimeConfig runtimeConfig = newRuntimeConfig(paths);

        // When / Then
        assertThrows(NullPointerException.class,
                () -> runtimeConfig.load(paths, null, RuntimeConfigTest::noMerge));
    }

    @Test
    void load_should_throw_when_merger_is_null() {
        // Given
        ConfigPaths paths = pathsTo(null, null);
        RuntimeConfig runtimeConfig = newRuntimeConfig(paths);

        // When / Then
        assertThrows(NullPointerException.class,
                () -> runtimeConfig.load(paths, ModelSettings.class, null));
    }

    @Test
    void refresh_should_merge_agents_with_project_override_and_backfill_agent_id() throws IOException {
        // Given
        Path globalAgents = writeFile("agents-global.json",
                "{\"defaultAgent\":\"global-agent\",\"agents\":{"
                        + "\"coder\":{\"systemPrompt\":\"global\"},"
                        + "\"writer\":{\"systemPrompt\":\"keep\"}}}");
        Path projectAgents = writeFile("agents-project.json",
                "{\"agents\":{\"coder\":{\"systemPrompt\":\"project\"},\"reviewer\":{}}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null),
                pathsTo(globalAgents, projectAgents), pathsTo(null, null));

        // Then：默认值项目级未配置 → 回退全局级
        assertEquals("global-agent", runtimeConfig.getAgentSettings().getDefaultAgent());
        // Then：同名 agent 整对象替换，顺序保留全局级位置，新 agent 追加在后
        assertEquals(Arrays.asList("coder", "writer", "reviewer"),
                new ArrayList<>(runtimeConfig.getAgentSettings().getAgents().keySet()));
        assertEquals("project",
                runtimeConfig.getAgentSettings().getAgents().get("coder").getSystemPrompt());
        assertEquals("keep", runtimeConfig.getAgentSettings().getAgents().get("writer").getSystemPrompt());
        // Then：agentId 由配置 key 回填
        assertEquals("writer", runtimeConfig.getAgentSettings().getAgents().get("writer").getAgentId());
    }

    @Test
    void refresh_should_warn_when_default_agent_not_declared() throws IOException {
        // Given
        Path agents = writeFile("agents.json", "{\"defaultAgent\":\"ghost\",\"agents\":{\"coder\":{}}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(agents, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));

        // When
        new RuntimeConfig(appConfig, new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher)
                .refresh();

        // Then
        assertTrue(publisher.warnings().stream().anyMatch(warning -> "agent".equals(warning.getSource())));
    }

    @Test
    void refresh_should_not_warn_when_no_agent_configured() {
        // Given
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));

        // When
        new RuntimeConfig(appConfig, new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher)
                .refresh();

        // Then：无 agent 是合法状态（全员 fail-open），不应发 agent 段告警
        assertTrue(publisher.warnings().stream().noneMatch(warning -> "agent".equals(warning.getSource())));
    }

    @Test
    void refresh_should_merge_plugins_and_expose_plugins_settings() throws IOException {
        // Given：扫描目录已迁到 config.json，jellyfish.json 的 plugins 段只剩名单与插件配置段
        Path global = writeFile("jellyfish-global.json",
                "{\"plugins\":{\"enabled\":[\"a\",\"b\"],"
                        + "\"configurations\":{\"p1\":{\"readOnlyTools\":[\"x\"]},\"p2\":{\"k\":\"global\"}}}}");
        Path project = writeFile("jellyfish-project.json",
                "{\"plugins\":{\"configurations\":{\"p2\":{\"k\":\"project\"}}}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then：列表段项目级非空则整体替换，为空则回退全局级
        PluginsSettings plugins = runtimeConfig.getPluginsSettings();
        assertEquals(Arrays.asList("a", "b"), plugins.getEnabled());
        // Then：配置段同名整对象替换、不同 key 追加
        assertEquals(Collections.singletonList("x"), plugins.getConfigurations().get("p1").get("readOnlyTools"));
        assertEquals("project", plugins.getConfigurations().get("p2").get("k"));
        // Then：转发入口与快照一致
        assertSame(plugins, runtimeConfig.getJellyfishSettings().getPlugins());
    }

    @Test
    void getPluginRoots_should_expand_tilde_and_skip_blank_entries() {
        // When
        RuntimeConfig runtimeConfig = newRuntimeConfigWithPlugins(new ConfigPaths(), new ConfigPaths(),
                new ConfigPaths(), new PluginPaths(Arrays.asList(" plugins ", "  ", "~/extra")));

        // Then：~ 展开、空白条目丢弃，非空白条目按顺序保留
        String home = System.getProperty("user.home");
        assertEquals(Arrays.asList(Paths.get("plugins"), Paths.get(home, "extra")), runtimeConfig.getPluginRoots());
    }

    @Test
    void getPluginRoots_should_return_empty_list_when_not_configured() {
        // When
        RuntimeConfig runtimeConfig = newRuntimeConfigWithPlugins(new ConfigPaths(), new ConfigPaths(),
                new ConfigPaths(), null);

        // Then：空列表交给 PluginRuntimeConfig 回退默认扫描目录
        assertTrue(runtimeConfig.getPluginRoots().isEmpty());
    }

    @Test
    void getPluginRoots_should_return_unmodifiable_list() {
        // When
        RuntimeConfig runtimeConfig = newRuntimeConfigWithPlugins(new ConfigPaths(), new ConfigPaths(),
                new ConfigPaths(), new PluginPaths(Collections.singletonList("plugins")));

        // Then
        assertThrows(UnsupportedOperationException.class,
                () -> runtimeConfig.getPluginRoots().add(Paths.get("other")));
    }

    @Test
    void refresh_should_merge_react_with_project_override() throws IOException {
        // Given：项目级只配了 maxRounds，其余字段应按缺省而不是按全局级
        Path global = writeFile("jellyfish-global.json",
                "{\"react\":{\"maxRounds\":3,\"contextReserveTokens\":100,\"maxToolOutputChars\":50}}");
        Path project = writeFile("jellyfish-project.json", "{\"react\":{\"maxRounds\":7}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then
        ReactSettings react = runtimeConfig.getReactSettings();
        assertEquals(7, react.getMaxRounds());
        assertEquals(ReactSettings.DEFAULT_CONTEXT_RESERVE_TOKENS, react.getContextReserveTokens());
        assertEquals(ReactSettings.DEFAULT_MAX_TOOL_OUTPUT_CHARS, react.getMaxToolOutputChars());
    }

    @Test
    void refresh_should_fall_back_to_global_react_when_project_absent() throws IOException {
        // Given
        Path global = writeFile("jellyfish-global.json", "{\"react\":{\"maxRounds\":9}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, null));

        // Then
        assertEquals(9, runtimeConfig.getReactSettings().getMaxRounds());
    }

    @Test
    void refresh_should_use_default_react_when_not_configured() {
        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(null, null));

        // Then
        assertTrue(runtimeConfig.getReactSettings().isDefault());
    }

    @Test
    void refresh_should_warn_when_plugin_enabled_and_disabled_at_once() throws IOException {
        // Given
        Path jellyfish = writeFile("jellyfish.json",
                "{\"plugins\":{\"enabled\":[\"a\"],\"disabled\":[\"a\"]}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(jellyfish, null));

        // When
        new RuntimeConfig(appConfig, new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher)
                .refresh();

        // Then
        assertTrue(publisher.warnings().stream().anyMatch(warning -> "a".equals(warning.getSource())));
    }

    /**
     * 用于验证 merger 返回 {@code null} 的场景。
     *
     * @param global  全局级配置
     * @param project 项目级配置
     * @return 恒为 {@code null}
     */
    private static ModelSettings noMerge(ModelSettings global, ModelSettings project) {
        return null;
    }

    /**
     * 构造被测对象，并让 {@link AppConfig} 返回给定双源路径。
     *
     * @param paths 模型配置段的双源路径
     * @return 已加载一次配置的 {@link RuntimeConfig}
     */
    private RuntimeConfig newRuntimeConfig(ConfigPaths paths) {
        when(appConfig.getModel()).thenReturn(paths);
        RuntimeConfig runtimeConfig = new RuntimeConfig(appConfig,
                new ConfigLoader(new SettingsReader(), new SettingsBinder()),
                new RecordingPublisher());
        runtimeConfig.refresh();
        return runtimeConfig;
    }

    /**
     * 构造被测对象，并让 {@link AppConfig} 返回给定两份双源路径。
     *
     * @param model     模型配置段的双源路径
     * @param agent     agent 配置段的双源路径
     * @param jellyfish 运行期设置段的双源路径
     * @return 已加载一次配置的 {@link RuntimeConfig}
     */
    private RuntimeConfig newRuntimeConfig(ConfigPaths model, ConfigPaths agent, ConfigPaths jellyfish) {
        when(appConfig.getModel()).thenReturn(model);
        when(appConfig.getAgent()).thenReturn(agent);
        when(appConfig.getJellyfish()).thenReturn(jellyfish);
        RuntimeConfig runtimeConfig = new RuntimeConfig(appConfig,
                new ConfigLoader(new SettingsReader(), new SettingsBinder()),
                new RecordingPublisher());
        runtimeConfig.refresh();
        return runtimeConfig;
    }

    /**
     * 构造被测对象，并让 {@link AppConfig} 返回含插件扫描目录的完整四段配置。
     *
     * @param model     模型配置段的双源路径
     * @param agent     agent 配置段的双源路径
     * @param jellyfish 运行期设置段的双源路径
     * @param plugins   插件扫描段，可为 {@code null}（视为未配置）
     * @return 已加载一次配置的 {@link RuntimeConfig}
     */
    private RuntimeConfig newRuntimeConfigWithPlugins(ConfigPaths model, ConfigPaths agent, ConfigPaths jellyfish,
                                                      PluginPaths plugins) {
        when(appConfig.getModel()).thenReturn(model);
        when(appConfig.getAgent()).thenReturn(agent);
        when(appConfig.getJellyfish()).thenReturn(jellyfish);
        when(appConfig.getPlugins()).thenReturn(plugins);
        RuntimeConfig runtimeConfig = new RuntimeConfig(appConfig,
                new ConfigLoader(new SettingsReader(), new SettingsBinder()),
                new RecordingPublisher());
        runtimeConfig.refresh();
        return runtimeConfig;
    }

    /**
     * 录制型通知发布入口：把发布的通知收集到内存，供「refresh 时发告警」这类断言使用。
     *
     * @author zcd
     */
    private static final class RecordingPublisher implements EventPublisher {

        /** 已发布的通知。 */
        private final List<JellyfishEvent> events = new ArrayList<>();

        @Override
        public void publish(JellyfishEvent event) {
            events.add(event);
        }

        /**
         * 获取收集到的配置告警。
         *
         * @return 配置告警列表
         */
        private List<ConfigWarningEvent> warnings() {
            List<ConfigWarningEvent> warnings = new ArrayList<>();
            for (JellyfishEvent event : events) {
                if (event instanceof ConfigWarningEvent) {
                    warnings.add((ConfigWarningEvent) event);
                }
            }
            return warnings;
        }
    }

    /**
     * 构造双源路径，{@code null} 表示该侧不配置。
     *
     * @param global  全局级文件路径，可为 {@code null}
     * @param project 项目级文件路径，可为 {@code null}
     * @return 双源路径对象
     */
    private static ConfigPaths pathsTo(Path global, Path project) {
        ConfigPaths paths = new ConfigPaths();
        paths.setGlobalPath(global == null ? "" : global.toString());
        paths.setProjectPath(project == null ? "" : project.toString());
        return paths;
    }

    /**
     * 在临时目录写入一个配置文件。
     *
     * @param name 文件名
     * @param json 文件内容
     * @return 写入后的文件路径
     * @throws IOException 写文件失败
     */
    private Path writeFile(String name, String json) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * 提取 provider 列表中的名称，用于断言顺序。
     *
     * @param providers provider 列表
     * @return provider 名称列表
     */
    private static List<String> providerNames(List<Provider> providers) {
        return providers.stream().map(Provider::getName).collect(Collectors.toList());
    }
}
