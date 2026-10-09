package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    /** 内置默认 agent 加载器：本测试只验证用户 agent 的双源合并，内置 agent 统一按不存在处理。 */
    @Mock
    BuiltinAgentLoader builtinAgentLoader;

    /**
     * 统一让内置 agent 返回 {@code null}：这样断言里的 agent 集合全部来自用户配置，
     * 与内置 agent 是否随构件发布解耦。用 {@code lenient} 是因为并非每个用例都会走到该加载。
     */
    @BeforeEach
    void ignoreBuiltinAgent() {
        lenient().when(builtinAgentLoader.load()).thenReturn(null);
    }

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
    void refresh_should_skip_project_config_when_not_trusted() throws IOException {
        // Given：项目级指向别处的 provider——它正是「在不可信仓库里启动」时最危险的那种内容
        Path global = writeFile("global.json",
                "{\"defaultProvider\":\"global-provider\",\"defaultModel\":\"global-model\","
                        + "\"providers\":{\"openai\":{\"type\":\"openai\",\"baseUrl\":\"https://global\"}}}");
        Path project = writeFile("project.json",
                "{\"defaultProvider\":\"evil-provider\",\"defaultModel\":\"evil-model\","
                        + "\"providers\":{\"openai\":{\"type\":\"openai\",\"baseUrl\":\"https://evil\"}}}");
        when(appConfig.getModel()).thenReturn(pathsTo(global, project));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));
        RecordingPublisher publisher = new RecordingPublisher();

        // When：没授予任何信任
        RuntimeConfig runtimeConfig = runtimeConfigWithTrust(
                new ProjectConfigTrust(tempDir.resolve("trust.json")), publisher);

        // Then：项目级整体不参与合并，只剩全局级
        assertEquals("global-provider", runtimeConfig.getDefaultProvider());
        assertEquals("https://global", runtimeConfig.getProviders().get(0).getBaseUrl());
        // Then：不能静默跳过——用户会以为自己的项目级配置生效了
        assertTrue(publisher.warnings().stream()
                        .anyMatch(warning -> "project-config".equals(warning.getSource())),
                "跳过未信任的项目级配置必须给出告警");
    }

    @Test
    void refresh_should_load_project_config_when_trustedByFingerprint() throws IOException {
        // Given
        Path global = writeFile("global.json", "{\"defaultProvider\":\"global-provider\"}");
        Path project = writeFile("project.json", "{\"defaultProvider\":\"project-provider\"}");
        when(appConfig.getModel()).thenReturn(pathsTo(global, project));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));
        ProjectConfigTrust trust = new ProjectConfigTrust(tempDir.resolve("trust.json"));
        assertTrue(trust.grant(project.toString()), "授予信任应当能写进信任仓库");

        // When
        RuntimeConfig runtimeConfig = runtimeConfigWithTrust(trust, new RecordingPublisher());

        // Then
        assertEquals("project-provider", runtimeConfig.getDefaultProvider());
    }

    @Test
    void refresh_should_drop_trust_when_project_config_contentChanged() throws IOException {
        // Given：信任之后文件被改过（例如仓库里 pull 下来一份新的配置）
        Path global = writeFile("global.json", "{\"defaultProvider\":\"global-provider\"}");
        Path project = writeFile("project.json", "{\"defaultProvider\":\"project-provider\"}");
        when(appConfig.getModel()).thenReturn(pathsTo(global, project));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));
        ProjectConfigTrust trust = new ProjectConfigTrust(tempDir.resolve("trust.json"));
        trust.grant(project.toString());
        writeFile("project.json", "{\"defaultProvider\":\"changed-provider\"}");

        // When
        RuntimeConfig runtimeConfig = runtimeConfigWithTrust(trust, new RecordingPublisher());

        // Then：指纹对不上就作废——否则「信任一次」等于「永远信任这个仓库」
        assertEquals("global-provider", runtimeConfig.getDefaultProvider());
    }

    @Test
    void refresh_should_skip_project_agents_when_not_trusted() throws IOException {
        // Given：项目级新增了一个 agent——未声明权限的 agent 是不受限的，等于凭空多一个身份
        Path globalAgents = writeFile("agents.json", "{\"agents\":{\"coder\":{}}}");
        Path projectAgents = writeFile("project-agents.json",
                "{\"agents\":{\"backdoor\":{}}}");
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(globalAgents, projectAgents));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigWithTrust(
                new ProjectConfigTrust(tempDir.resolve("trust.json")), new RecordingPublisher());

        // Then：只有全局级的 agent
        assertEquals(Collections.singletonList("coder"),
                new ArrayList<String>(runtimeConfig.getAgentSettings().getAgents().keySet()));
    }

    @Test
    void refresh_should_trustProjectConfigForThisRun_when_flagIsSet() throws IOException {
        // Given
        Path global = writeFile("global.json", "{\"defaultProvider\":\"global-provider\"}");
        Path project = writeFile("project.json", "{\"defaultProvider\":\"project-provider\"}");
        when(appConfig.getModel()).thenReturn(pathsTo(global, project));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));
        ProjectConfigTrust trust = new ProjectConfigTrust(tempDir.resolve("trust.json"));
        trust.trustEverythingInThisRun();

        // When
        RuntimeConfig runtimeConfig = runtimeConfigWithTrust(trust, new RecordingPublisher());

        // Then：--trust-project-config 的语义就是「这次直接用」，不落盘也不看指纹
        assertEquals("project-provider", runtimeConfig.getDefaultProvider());
        assertTrue(trust.isTrustingEverythingInThisRun());
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
        when(configLoader.read(eq(shared.toString()), eq(ModelSettings.class), any()))
                .thenReturn(new ModelSettings(null, null, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(configLoader, new RecordingPublisher());
        runtimeConfig.refresh();

        // Then：同一路径只读取一次
        verify(configLoader, times(1)).read(eq(shared.toString()), eq(ModelSettings.class), any());
    }

    @Test
    void refresh_should_publish_warning_when_config_has_unknown_field() throws IOException {
        // Given：一份把 defaultModel 拼成 defaultModle 的 models.json——绑定会照常成功（容忍未知字段），
        // 因此没有这条告警的话，用户看到的就是「配置明明写了却没作用」
        Path models = writeFile("models.json", "{\"defaultModle\":\"m\"}");
        when(appConfig.getModel()).thenReturn(pathsTo(models, null));
        RecordingPublisher publisher = new RecordingPublisher();

        // When
        runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher).refresh();

        // Then：告警里既有文件路径也有字段名，用户才找得到
        assertTrue(publisher.warnings().stream().anyMatch(warning -> models.toString().equals(warning.getSource())
                        && warning.getMessage().contains("defaultModle")),
                publisher.warnings().toString());
    }

    @Test
    void refresh_should_not_publish_unknownField_warning_when_configIsClean() throws IOException {
        // Given：字段全对的一份配置
        Path models = writeFile("clean.json", "{\"defaultProvider\":\"p\",\"providers\":{}}");
        when(appConfig.getModel()).thenReturn(pathsTo(models, null));
        RecordingPublisher publisher = new RecordingPublisher();

        // When
        runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher).refresh();

        // Then：误报会让这条告警变成噪音，用户很快学会忽略它
        assertTrue(publisher.warnings().stream().noneMatch(warning -> warning.getMessage().contains("不认识的字段")),
                publisher.warnings().toString());
    }

    @Test
    void refresh_should_publish_warning_when_file_missing() {
        // Given
        Path missing = tempDir.resolve("missing.json");
        ConfigPaths paths = pathsTo(missing, null);
        when(appConfig.getModel()).thenReturn(paths);
        RecordingPublisher publisher = new RecordingPublisher();

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
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
    void refresh_should_merge_agents_with_md_prompt_per_source() throws IOException {
        // Given：两个源各在自己的目录里，提示词随源加载
        Path globalDir = Files.createDirectories(tempDir.resolve("global"));
        Path projectDir = Files.createDirectories(tempDir.resolve("project"));
        Path globalAgents = writeFile(globalDir.resolve("agents.json"),
                "{\"agents\":{\"coder\":{},\"writer\":{}}}");
        Path projectAgents = writeFile(projectDir.resolve("agents.json"),
                "{\"agents\":{\"coder\":{},\"reviewer\":{}}}");
        writeFile(globalDir.resolve("coder.md"), "global-coder");
        writeFile(globalDir.resolve("writer.md"), "keep");
        writeFile(projectDir.resolve("coder.md"), "project-coder");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null),
                pathsTo(globalAgents, projectAgents), pathsTo(null, null));

        // Then：同名 agent 整对象替换，顺序保留全局级位置，新 agent 追加在后
        assertEquals(Arrays.asList("coder", "writer", "reviewer"),
                new ArrayList<>(runtimeConfig.getAgentSettings().getAgents().keySet()));
        // Then：提示词与定义一起被项目级覆盖
        assertEquals("project-coder",
                runtimeConfig.getAgentSettings().getAgents().get("coder").getSystemPrompt());
        assertEquals("keep", runtimeConfig.getAgentSettings().getAgents().get("writer").getSystemPrompt());
        // Then：项目级没有 reviewer.md，该 agent 仍存在但无提示词
        assertNull(runtimeConfig.getAgentSettings().getAgents().get("reviewer").getSystemPrompt());
        // Then：agentId 由配置 key 回填
        assertEquals("writer", runtimeConfig.getAgentSettings().getAgents().get("writer").getAgentId());
    }

    @Test
    void refresh_should_drop_agent_with_unsafe_id_and_warn() throws IOException {
        // Given：agentId 同时是提示词文件名，含路径分隔符的 key 不该被拼进文件路径
        Path agents = writeFile("agents.json", "{\"agents\":{\"../evil\":{},\"coder\":{}}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(agents, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
        runtimeConfig.refresh();

        // Then：非法条目整条丢弃，合法条目保留，并给出告警
        assertTrue(runtimeConfig.getAgentSettings().getAgents().containsKey("coder"));
        assertTrue(!runtimeConfig.getAgentSettings().getAgents().containsKey("../evil"));
        assertTrue(publisher.warnings().stream().anyMatch(warning -> "../evil".equals(warning.getSource())));
    }

    @Test
    void refresh_should_not_warn_when_no_agent_configured() {
        // Given
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));

        // When
        runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher)
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

        // Then：列表段项目级已声明则整体替换，未声明则回退全局级
        PluginsSettings plugins = runtimeConfig.getPluginsSettings();
        assertEquals(Arrays.asList("a", "b"), plugins.getEnabled());
        // Then：配置段同名整对象替换、不同 key 追加
        assertEquals(Collections.singletonList("x"), plugins.getConfigurations().get("p1").get("readOnlyTools"));
        assertEquals("project", plugins.getConfigurations().get("p2").get("k"));
        // Then：转发入口与快照一致
        assertSame(plugins, runtimeConfig.getJellyfishSettings().getPlugins());
    }

    @Test
    void refresh_should_let_project_declared_empty_lists_override_global() throws IOException {
        // Given：项目级显式写空数组的语义是「本层一个都不要」，不能回退全局名单
        Path global = writeFile("jellyfish-global-lists.json",
                "{\"plugins\":{\"enabled\":[\"a\",\"b\"],\"disabled\":[\"c\"]}}");
        Path project = writeFile("jellyfish-project-lists.json",
                "{\"plugins\":{\"enabled\":[],\"disabled\":[]}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then
        PluginsSettings plugins = runtimeConfig.getPluginsSettings();
        assertTrue(plugins.isEnabledDeclared());
        assertTrue(plugins.getEnabled().isEmpty());
        assertTrue(plugins.isDisabledDeclared());
        assertTrue(plugins.getDisabled().isEmpty());
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
    void refresh_should_merge_permission_with_project_override() throws IOException {
        // Given：项目级整对象覆盖全局级
        Path global = writeFile("jellyfish-global.json", "{\"permission\":{\"approvalTimeoutSeconds\":30}}");
        Path project = writeFile("jellyfish-project.json", "{\"permission\":{\"approvalTimeoutSeconds\":5}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then
        assertEquals(5, runtimeConfig.getPermissionApprovalSettings().getApprovalTimeoutSeconds());
    }

    @Test
    void refresh_should_fall_back_to_global_permission_when_project_absent() throws IOException {
        // Given
        Path global = writeFile("jellyfish-global.json", "{\"permission\":{\"approvalTimeoutSeconds\":45}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, null));

        // Then
        assertEquals(45, runtimeConfig.getPermissionApprovalSettings().getApprovalTimeoutSeconds());
    }

    @Test
    void refresh_should_use_default_permission_when_not_configured() {
        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(null, null));

        // Then
        assertTrue(runtimeConfig.getPermissionApprovalSettings().isDefault());
        assertEquals(PermissionApprovalSettings.DEFAULT_APPROVAL_TIMEOUT_SECONDS,
                runtimeConfig.getPermissionApprovalSettings().getApprovalTimeoutSeconds());
    }

    @Test
    void refresh_should_fall_back_to_global_ask_when_project_does_not_declare_it() throws IOException {
        // Given：全局级把提问配成「永不超时」，项目级只写了别的段
        Path global = writeFile("jellyfish-global-ask.json", "{\"ask\":{\"timeoutSeconds\":0}}");
        Path project = writeFile("jellyfish-project-other.json",
                "{\"permission\":{\"approvalTimeoutSeconds\":5}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then：项目级没写这一段，全局级配的「永不超时」必须留下来——
        // 按「取到的段是否非空」判断时它会顶成缺省 120，且不产生任何告警
        assertTrue(runtimeConfig.getAskSettings().isInfinite());
    }

    @Test
    void refresh_should_fall_back_to_global_permission_when_project_does_not_declare_it() throws IOException {
        // Given
        Path global = writeFile("jellyfish-global-permission.json",
                "{\"permission\":{\"approvalTimeoutSeconds\":45}}");
        Path project = writeFile("jellyfish-project-ask.json", "{\"ask\":{\"timeoutSeconds\":30}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then
        assertEquals(45, runtimeConfig.getPermissionApprovalSettings().getApprovalTimeoutSeconds());
        assertEquals(30, runtimeConfig.getAskSettings().getTimeoutSeconds(),
                "项目级写了 ask 段，这一份就该按项目级生效");
    }

    @Test
    void refresh_should_fall_back_to_global_react_when_project_does_not_declare_it() throws IOException {
        // Given
        Path global = writeFile("jellyfish-global-react.json", "{\"react\":{\"maxRounds\":9}}");
        Path project = writeFile("jellyfish-project-ask-only.json", "{\"ask\":{\"timeoutSeconds\":30}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then：项目级没写 react 段，回退全局级（缺省是 16，因此 9 只有来自全局级才可能出现）
        assertEquals(9, runtimeConfig.getReactSettings().getMaxRounds());
    }

    @Test
    void refresh_should_fall_back_to_global_sub_agent_when_project_does_not_declare_it() throws IOException {
        // Given
        Path global = writeFile("jellyfish-global-subagent.json", "{\"subAgent\":{\"maxDepth\":3}}");
        Path project = writeFile("jellyfish-project-ask.json", "{\"ask\":{\"timeoutSeconds\":30}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then
        assertEquals(3, runtimeConfig.getSubAgentSettings().getMaxDepth());
    }

    @Test
    void refresh_should_fall_back_to_global_plugins_when_project_does_not_declare_it() throws IOException {
        // Given：插件段的段内合并本就是逐键的，这里钉住「整段层面的未声明」不会连累它
        Path global = writeFile("jellyfish-global-plugins.json", "{\"plugins\":{\"enabled\":[\"plugin-a\"]}}");
        Path project = writeFile("jellyfish-project-ask.json", "{\"ask\":{\"timeoutSeconds\":30}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then
        assertEquals(Collections.singletonList("plugin-a"),
                runtimeConfig.getJellyfishSettings().getPlugins().getEnabled());
    }

    @Test
    void refresh_should_treat_empty_section_as_declared() throws IOException {
        // Given：全局级把提问配成「永不超时」，项目级显式写了空的 ask 段
        Path global = writeFile("jellyfish-global-ask.json", "{\"ask\":{\"timeoutSeconds\":0}}");
        Path project = writeFile("jellyfish-project-empty-ask.json", "{\"ask\":{}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then：写了这一段就是声明，整段覆盖（哪怕内容全是缺省值）——这是不逐字段合并的必然结果，
        // 也是把「没写」与「写了空对象」分开判定的意义所在
        assertEquals(AskSettings.DEFAULT_TIMEOUT_SECONDS,
                runtimeConfig.getAskSettings().getTimeoutSeconds());
    }

    @Test
    void refresh_should_merge_sub_agent_with_project_override() throws IOException {
        // Given：项目级只配了 maxDepth，其余字段应按缺省而不是按全局级（整对象覆盖）
        Path global = writeFile("jellyfish-global.json",
                "{\"subAgent\":{\"enabled\":false,\"maxDepth\":5,\"maxRounds\":3}}");
        Path project = writeFile("jellyfish-project.json", "{\"subAgent\":{\"maxDepth\":0}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, project));

        // Then
        SubAgentSettings subAgent = runtimeConfig.getSubAgentSettings();
        assertEquals(0, subAgent.getMaxDepth());
        assertTrue(subAgent.isEnabled());
        assertEquals(SubAgentSettings.DEFAULT_MAX_ROUNDS, subAgent.getMaxRounds());
    }

    @Test
    void refresh_should_fall_back_to_global_sub_agent_when_project_absent() throws IOException {
        // Given
        Path global = writeFile("jellyfish-global.json", "{\"subAgent\":{\"maxSpawnsPerTurn\":4}}");

        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(global, null));

        // Then
        assertEquals(4, runtimeConfig.getSubAgentSettings().getMaxSpawnsPerTurn());
    }

    @Test
    void refresh_should_use_default_sub_agent_when_not_configured() {
        // When
        RuntimeConfig runtimeConfig = newRuntimeConfig(pathsTo(null, null), pathsTo(null, null),
                pathsTo(null, null));

        // Then
        assertTrue(runtimeConfig.getSubAgentSettings().isDefault());
        assertEquals(SubAgentSettings.DEFAULT_MAX_DEPTH, runtimeConfig.getSubAgentSettings().getMaxDepth());
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
        runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher)
                .refresh();

        // Then
        assertTrue(publisher.warnings().stream().anyMatch(warning -> "a".equals(warning.getSource())));
    }

    @Test
    void refresh_should_warn_when_approval_timeout_is_negative() throws IOException {
        // Given：负数不是合法值，会被换成缺省 120 秒——而用户可能以为它立刻生效或立刻拒绝
        Path jellyfish = writeFile("jellyfish-permission.json",
                "{\"permission\":{\"approvalTimeoutSeconds\":-1}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(jellyfish, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
        runtimeConfig.refresh();

        // Then：生效的值与告警都要如实
        assertEquals(PermissionApprovalSettings.DEFAULT_APPROVAL_TIMEOUT_SECONDS,
                runtimeConfig.getPermissionApprovalSettings().getApprovalTimeoutSeconds());
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "permission".equals(warning.getSource())
                        && warning.getMessage().contains("approvalTimeoutSeconds=-1")),
                publisher.warnings().toString());
    }

    @Test
    void refresh_should_keep_zero_approval_timeout_and_warn_about_never_timeout() throws IOException {
        // Given：0 表示永不超时（不是非法值），因此它必须生效，但代价要让人看见
        Path jellyfish = writeFile("jellyfish-permission-forever.json",
                "{\"permission\":{\"approvalTimeoutSeconds\":0}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(jellyfish, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
        runtimeConfig.refresh();

        // Then：值照用户写的生效，同时有一条来源为 permission 的告警说清后果
        assertTrue(runtimeConfig.getPermissionApprovalSettings().isInfinite());
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "permission".equals(warning.getSource())
                        && warning.getMessage().contains("approvalTimeoutSeconds=0")
                        && warning.getMessage().contains("永不超时")),
                publisher.warnings().toString());
    }

    @Test
    void refresh_should_keep_zero_ask_timeout_and_warn_about_never_timeout() throws IOException {
        // Given：提问侧同口径——0 表示永不超时，且此前它会被静默换成 120 秒
        Path jellyfish = writeFile("jellyfish-ask-forever.json",
                "{\"ask\":{\"timeoutSeconds\":0}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(jellyfish, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
        runtimeConfig.refresh();

        // Then
        assertTrue(runtimeConfig.getAskSettings().isInfinite());
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "ask".equals(warning.getSource())
                        && warning.getMessage().contains("timeoutSeconds=0")
                        && warning.getMessage().contains("永不超时")),
                publisher.warnings().toString());
    }

    @Test
    void refresh_should_warn_when_ask_timeout_is_negative() throws IOException {
        // Given：提问侧的负数同样是写错了，回退缺省并报出
        Path jellyfish = writeFile("jellyfish-ask-negative.json",
                "{\"ask\":{\"timeoutSeconds\":-5}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(jellyfish, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
        runtimeConfig.refresh();

        // Then
        assertEquals(AskSettings.DEFAULT_TIMEOUT_SECONDS, runtimeConfig.getAskSettings().getTimeoutSeconds());
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "ask".equals(warning.getSource())
                        && warning.getMessage().contains("timeoutSeconds=-5")),
                publisher.warnings().toString());
    }

    @Test
    void refresh_should_warn_when_react_values_are_invalid() throws IOException {
        // Given：react 段写错两个值（一个非正、一个越界），而实现是回退缺省
        Path jellyfish = writeFile("jellyfish-react.json",
                "{\"react\":{\"maxRounds\":0,\"autoCompactPercent\":150}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(jellyfish, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
        runtimeConfig.refresh();

        // Then：事件面是这类问题的唯一出口（TUI 下日志只进文件），因此两条都要能看见
        assertEquals(ReactSettings.DEFAULT_MAX_ROUNDS, runtimeConfig.getReactSettings().getMaxRounds());
        assertEquals(100, runtimeConfig.getReactSettings().getAutoCompactPercent());
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "react".equals(warning.getSource()) && warning.getMessage().contains("maxRounds=0")),
                publisher.warnings().toString());
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "react".equals(warning.getSource()) && warning.getMessage().contains("autoCompactPercent=150")),
                publisher.warnings().toString());
    }

    @Test
    void refresh_should_warn_when_sampling_values_are_invalid() throws IOException {
        // Given：provider 的 sampling 段写了一组非法值（内核的处理是静默丢弃该项）
        Path models = writeFile("models-sampling.json",
                "{\"providers\":{\"openai\":{\"type\":\"openai\",\"sampling\":{\"temperature\":-1,\"topK\":0}}}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(models, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(null, null));

        // When
        runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher).refresh();

        // Then：来源是 provider 名（同一份文件里可能有好几个 provider），消息里带键名与原值
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "openai".equals(warning.getSource()) && warning.getMessage().contains("sampling.temperature=-1.0")),
                publisher.warnings().toString());
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "openai".equals(warning.getSource()) && warning.getMessage().contains("sampling.topK=0")),
                publisher.warnings().toString());
    }

    @Test
    void refresh_should_warn_when_approval_timeout_is_absurdly_large() throws IOException {
        // Given：写下一个远超人工审批所需的秒数（多半是笔误），而它会让 react 线程同步等这么久
        Path jellyfish = writeFile("jellyfish-permission-long.json",
                "{\"permission\":{\"approvalTimeoutSeconds\":99999}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(jellyfish, null));

        // When
        RuntimeConfig runtimeConfig = runtimeConfigOf(
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher);
        runtimeConfig.refresh();

        // Then：值照旧生效（只是告警），因为回退与拒绝都不是这里该做的判断
        assertEquals(99999, runtimeConfig.getPermissionApprovalSettings().getApprovalTimeoutSeconds());
        assertTrue(publisher.warnings().stream().anyMatch(warning ->
                "permission".equals(warning.getSource())
                        && warning.getMessage().contains("99999")));
    }

    @Test
    void refresh_should_not_warn_for_reasonable_approval_timeout() throws IOException {
        // Given：一个正常的值不该带来任何关于审批超时的噪声
        Path jellyfish = writeFile("jellyfish-permission-ok.json",
                "{\"permission\":{\"approvalTimeoutSeconds\":45}}");
        RecordingPublisher publisher = new RecordingPublisher();
        when(appConfig.getModel()).thenReturn(pathsTo(null, null));
        when(appConfig.getAgent()).thenReturn(pathsTo(null, null));
        when(appConfig.getJellyfish()).thenReturn(pathsTo(jellyfish, null));

        // When
        runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher).refresh();

        // Then
        assertTrue(publisher.warnings().stream().noneMatch(warning ->
                "permission".equals(warning.getSource())));
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
        RuntimeConfig runtimeConfig = runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()),
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
        RuntimeConfig runtimeConfig = runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()),
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
        RuntimeConfig runtimeConfig = runtimeConfigOf(new ConfigLoader(new SettingsReader(), new SettingsBinder()),
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
        return writeFile(tempDir.resolve(name), json);
    }

    /**
     * 向指定路径写入内容，用于目录分层的用例（每层各自有 {@code agents.json} 与 {@code *.md}）。
     *
     * @param file    目标文件路径
     * @param content 文件内容
     * @return 写入后的文件路径
     * @throws IOException 写文件失败
     */
    private Path writeFile(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * 用真实读取链构造被测对象，并注入内置 agent 的桩。
     *
     * @param configLoader 配置文件读取门面
     * @param publisher    通知发布入口
     * @return 尚未 {@code refresh} 的 {@link RuntimeConfig}
     */
    private RuntimeConfig runtimeConfigOf(ConfigLoader configLoader, EventPublisher publisher) {
        // 既有用例关心的是「双源怎么合并」，与「项目级该不该被信任」是两件事：
        // 这里统一按「本次进程信任全部项目级配置」构造，把信任闸的影响摘出去；
        // 闸门本身由 refresh_should_ 开头的那组用例单独覆盖
        ProjectConfigTrust trust = trustingAllProjectConfigs();
        return new RuntimeConfig(appConfig, configLoader, publisher, builtinAgentLoader,
                new AgentPromptLoader(new SettingsReader()), trust);
    }

    /**
     * 构造信任裁决：一个不落盘、本次进程信任全部项目级配置的实例。
     *
     * @return 信任裁决
     */
    private static ProjectConfigTrust trustingAllProjectConfigs() {
        ProjectConfigTrust trust = new ProjectConfigTrust(Paths.get("target", "unused-trust-store.json"));
        trust.trustEverythingInThisRun();
        return trust;
    }

    /**
     * 用给定的信任裁决构造被测对象并加载一次配置，供「项目级该不该被加载」的用例使用。
     *
     * @param trust     信任裁决，不可为 {@code null}
     * @param publisher 通知发布入口，不可为 {@code null}
     * @return 已加载一次配置的 {@link RuntimeConfig}
     */
    private RuntimeConfig runtimeConfigWithTrust(ProjectConfigTrust trust, EventPublisher publisher) {
        RuntimeConfig runtimeConfig = new RuntimeConfig(appConfig,
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), publisher,
                builtinAgentLoader, new AgentPromptLoader(new SettingsReader()), trust);
        runtimeConfig.refresh();
        return runtimeConfig;
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
