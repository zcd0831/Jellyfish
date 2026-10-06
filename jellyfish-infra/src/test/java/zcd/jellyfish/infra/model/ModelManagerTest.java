package zcd.jellyfish.infra.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ModelsLoadedEvent;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SamplingSettings;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.api.extension.ModelCatalogRequest;
import zcd.jellyfish.api.extension.ModelCatalogResult;
import zcd.jellyfish.api.extension.ModelDescriptor;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.llm.LlmClientFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModelManager} 的单元测试：验证「注册 → 解析 → 路由」三段职责，以及
 * 「不持有全局当前模型、解析失败才抛错」两条核心约定。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class ModelManagerTest {

    /** 运行时配置门面。 */
    @Mock
    RuntimeConfig runtimeConfig;

    /** provider / model 索引。 */
    @Mock
    ModelRegistry modelRegistry;

    /** LLM 客户端工厂。 */
    @Mock
    LlmClientFactory llmClientFactory;

    /** 通知发布入口，用于验证装载事件。 */
    @Mock
    EventPublisher events;

    /** 扩展注册表：用真实实现，目录发现本身就是「注册 + 按路由键查找」这条链路。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(new TypeRegistry());

    /** provider 名。 */
    private static final String PROVIDER = "openai";

    /** model 名。 */
    private static final String MODEL = "gpt-4o";

    @Test
    void constructor_should_build_index_without_resolving_default_when_config_empty() {
        // When
        newModelManager();

        // Then
        verify(modelRegistry).refresh(Collections.<Provider>emptyList());
        verify(runtimeConfig, never()).getDefaultProvider();
        verify(runtimeConfig, never()).getDefaultModel();
        // 构造期总线可能尚未启动，不能广播事件
        verify(events, never()).publish(any());
    }

    @Test
    void refresh_should_publish_models_loaded_event_when_rebuilt() {
        // Given
        Provider provider = provider();
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(modelRegistry.getProviders()).thenReturn(Collections.singletonList(provider));
        when(runtimeConfig.getDefaultProvider()).thenReturn(PROVIDER);
        when(runtimeConfig.getDefaultModel()).thenReturn(MODEL);
        ModelManager manager = newModelManager();

        // When
        manager.refresh(false);

        // Then
        ArgumentCaptor<ModelsLoadedEvent> captor = ArgumentCaptor.forClass(ModelsLoadedEvent.class);
        verify(events).publish(captor.capture());
        assertEquals(PROVIDER, captor.getValue().getDefaultProvider());
        assertEquals(MODEL, captor.getValue().getDefaultModel());
        assertEquals(Collections.singleton(PROVIDER), captor.getValue().getProviderNames());
    }

    @Test
    void refresh_should_publish_empty_event_when_no_provider_configured() {
        // Given
        when(runtimeConfig.getProviders()).thenReturn(Collections.<Provider>emptyList());
        ModelManager manager = newModelManager();

        // When
        manager.refresh(false);

        // Then
        ArgumentCaptor<ModelsLoadedEvent> captor = ArgumentCaptor.forClass(ModelsLoadedEvent.class);
        verify(events).publish(captor.capture());
        assertTrue(captor.getValue().getProviderNames().isEmpty());
        assertNull(captor.getValue().getDefaultProvider());
    }

    @Test
    void refresh_should_keep_index_when_event_publish_fails() {
        // Given
        when(runtimeConfig.getProviders()).thenReturn(Collections.<Provider>emptyList());
        doThrow(new IllegalStateException("bus down")).when(events).publish(any());
        ModelManager manager = newModelManager();

        // When
        manager.refresh(true);

        // Then
        verify(modelRegistry, times(2)).refresh(Collections.<Provider>emptyList());
    }

    @Test
    void refresh_should_reload_config_and_clear_cache_when_reload_config_true() {
        // Given
        when(runtimeConfig.getProviders()).thenReturn(Collections.<Provider>emptyList());
        ModelManager manager = newModelManager();

        // When
        manager.refresh(true);

        // Then
        verify(runtimeConfig).refresh();
        verify(llmClientFactory).clearCache();
        verify(modelRegistry, times(2)).refresh(Collections.<Provider>emptyList());
    }

    @Test
    void refresh_should_only_rebuild_index_when_reload_config_false() {
        // Given
        when(runtimeConfig.getProviders()).thenReturn(Collections.<Provider>emptyList());
        ModelManager manager = newModelManager();

        // When
        manager.refresh(false);

        // Then
        verify(runtimeConfig, never()).refresh();
        verify(llmClientFactory, never()).clearCache();
        verify(modelRegistry, times(2)).refresh(Collections.<Provider>emptyList());
    }

    @Test
    void resolve_should_throw_when_provider_or_model_blank() {
        // Given
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, () -> manager.resolve("", MODEL));
        assertThrows(JellyfishException.class, () -> manager.resolve(PROVIDER, " "));
        assertThrows(JellyfishException.class, () -> manager.resolve(null, null));
    }

    @Test
    void resolve_should_throw_when_model_not_found() {
        // Given
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider());
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, () -> manager.resolve(PROVIDER, MODEL));
    }

    @Test
    void resolve_should_throw_when_provider_not_found() {
        // Given
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, () -> manager.resolve(PROVIDER, MODEL));
    }

    @Test
    void resolve_should_return_resolved_model_when_found() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider);
        when(modelRegistry.findModel(PROVIDER, MODEL)).thenReturn(model);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolve(PROVIDER, MODEL);

        // Then
        assertSame(provider, resolved.getProvider());
        assertSame(model, resolved.getModel());
        assertEquals(PROVIDER, resolved.getProviderName());
        assertEquals(MODEL, resolved.getModelName());
    }

    @Test
    void resolveDefault_should_match_exactly_when_both_defaults_configured() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        when(runtimeConfig.getDefaultProvider()).thenReturn(PROVIDER);
        when(runtimeConfig.getDefaultModel()).thenReturn(MODEL);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider);
        when(modelRegistry.findModel(PROVIDER, MODEL)).thenReturn(model);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolveDefault();

        // Then
        assertSame(model, resolved.getModel());
    }

    @Test
    void resolveDefault_should_take_first_provider_with_model_when_no_defaults() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        when(modelRegistry.getProviders()).thenReturn(Collections.singletonList(provider));
        when(modelRegistry.firstModel(PROVIDER)).thenReturn(model);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolveDefault();

        // Then
        assertSame(provider, resolved.getProvider());
        assertSame(model, resolved.getModel());
    }

    @Test
    void resolveDefault_should_take_first_model_when_only_provider_configured() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        when(runtimeConfig.getDefaultProvider()).thenReturn(PROVIDER);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider);
        when(modelRegistry.firstModel(PROVIDER)).thenReturn(model);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolveDefault();

        // Then
        assertSame(model, resolved.getModel());
    }

    @Test
    void resolveDefault_should_pick_first_provider_by_model_name_when_only_model_configured() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        Map<String, Model> matches = new LinkedHashMap<>();
        matches.put(PROVIDER, model);
        when(runtimeConfig.getDefaultModel()).thenReturn(MODEL);
        when(modelRegistry.findProvidersByModelName(MODEL)).thenReturn(matches);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolveDefault();

        // Then
        assertSame(provider, resolved.getProvider());
        assertSame(model, resolved.getModel());
    }

    @Test
    void resolveDefault_should_throw_when_no_provider_has_model() {
        // Given
        when(modelRegistry.getProviders()).thenReturn(Collections.<Provider>emptyList());
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, manager::resolveDefault);
    }

    @Test
    void resolveDefault_should_throw_when_default_model_not_found() {
        // Given
        when(runtimeConfig.getDefaultProvider()).thenReturn(PROVIDER);
        when(runtimeConfig.getDefaultModel()).thenReturn(MODEL);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider());
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, manager::resolveDefault);
    }

    @Test
    void resolveDefault_should_throw_when_no_provider_provides_default_model() {
        // Given
        when(runtimeConfig.getDefaultModel()).thenReturn(MODEL);
        when(modelRegistry.findProvidersByModelName(MODEL)).thenReturn(Collections.<String, Model>emptyMap());
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, manager::resolveDefault);
    }

    @Test
    void resolveDefault_should_throw_when_matched_provider_not_found() {
        // Given
        Map<String, Model> matches = new LinkedHashMap<>();
        matches.put("ghost", provider().getModels().get(0));
        when(runtimeConfig.getDefaultModel()).thenReturn(MODEL);
        when(modelRegistry.findProvidersByModelName(MODEL)).thenReturn(matches);
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, manager::resolveDefault);
    }

    @Test
    void resolveDefault_should_throw_when_default_provider_has_no_model() {
        // Given
        when(runtimeConfig.getDefaultProvider()).thenReturn(PROVIDER);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider());
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, manager::resolveDefault);
    }

    @Test
    void resolveDefault_should_skip_provider_without_model_when_no_defaults() {
        // Given
        Provider withoutModel = new Provider("azure", "openai", "api-key", "https://api.example.com",
                Collections.<Model>emptyList());
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        when(modelRegistry.getProviders()).thenReturn(Arrays.<Provider>asList(null, withoutModel, provider));
        when(modelRegistry.firstModel(PROVIDER)).thenReturn(model);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolveDefault();

        // Then
        assertSame(provider, resolved.getProvider());
        assertSame(model, resolved.getModel());
    }

    @Test
    void resolveDefault_should_skip_provider_returning_null_models_when_no_defaults() {
        // Given：Provider 约定 models 为空列表而非 null，这里覆写以验证选择逻辑的容错
        Provider nullModels = new Provider("azure", "openai", "api-key", "https://api.example.com", null) {
            @Override
            public List<Model> getModels() {
                return null;
            }
        };
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        when(modelRegistry.getProviders()).thenReturn(Arrays.<Provider>asList(nullModels, provider));
        when(modelRegistry.firstModel(PROVIDER)).thenReturn(model);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolveDefault();

        // Then
        assertSame(model, resolved.getModel());
    }

    @Test
    void resolveDefault_should_throw_when_all_providers_lack_model() {
        // Given
        Provider withoutModel = new Provider("azure", "openai", "api-key", "https://api.example.com",
                Collections.<Model>emptyList());
        when(modelRegistry.getProviders()).thenReturn(Arrays.<Provider>asList(null, withoutModel));
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, manager::resolveDefault);
    }

    @Test
    void getClient_should_delegate_to_factory_when_resolved_model_given() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        LlmClient client = mock(LlmClient.class);
        when(llmClientFactory.getClient(provider)).thenReturn(client);
        ModelManager manager = newModelManager();

        // When
        LlmClient result = manager.getClient(new ResolvedModel(provider, model));

        // Then
        assertSame(client, result);
    }

    @Test
    void getClient_should_throw_when_resolved_model_null() {
        // Given
        ModelManager manager = newModelManager();

        // When / Then
        assertThrows(JellyfishException.class, () -> manager.getClient((ResolvedModel) null));
    }

    @Test
    void getClient_should_resolve_then_delegate_when_names_given() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        LlmClient client = mock(LlmClient.class);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider);
        when(modelRegistry.findModel(PROVIDER, MODEL)).thenReturn(model);
        when(llmClientFactory.getClient(provider)).thenReturn(client);
        ModelManager manager = newModelManager();

        // When
        LlmClient result = manager.getClient(PROVIDER, MODEL);

        // Then
        assertSame(client, result);
    }

    @Test
    void lookup_methods_should_delegate_to_registry() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        when(modelRegistry.getProviders()).thenReturn(Collections.singletonList(provider));
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider);
        when(modelRegistry.findModel(PROVIDER, MODEL)).thenReturn(model);
        ModelManager manager = newModelManager();

        // When / Then
        assertEquals(Collections.singletonList(provider), manager.getProviders());
        assertSame(provider, manager.findProvider(PROVIDER));
        assertSame(model, manager.findModel(PROVIDER, MODEL));
    }

    @Test
    void resolveReference_should_split_when_provider_and_model_given() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider);
        when(modelRegistry.findModel(PROVIDER, MODEL)).thenReturn(model);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolveReference("openai/gpt-4o");

        // Then
        assertSame(provider, resolved.getProvider());
        assertSame(model, resolved.getModel());
    }

    @Test
    void resolveReference_should_pick_first_provider_when_bare_model_given() {
        // Given
        Provider provider = provider();
        Model model = provider.getModels().get(0);
        Map<String, Model> matches = new LinkedHashMap<String, Model>();
        matches.put(PROVIDER, model);
        when(modelRegistry.findProvidersByModelName(MODEL)).thenReturn(matches);
        when(modelRegistry.findProvider(PROVIDER)).thenReturn(provider);
        ModelManager manager = newModelManager();

        // When
        ResolvedModel resolved = manager.resolveReference(MODEL);

        // Then
        assertSame(model, resolved.getModel());
    }

    @Test
    void resolveReference_should_treat_trailing_slash_as_model_name_and_fail() {
        // Given：斜杠必须在中间才算分隔符，"gpt-4o/" 整串被当成模型名
        when(modelRegistry.findProvidersByModelName(MODEL + "/"))
                .thenReturn(Collections.<String, Model>emptyMap());
        ModelManager manager = newModelManager();

        // When / Then：报「模型不存在」而不是「provider 名为空」——后者对用户毫无指导价值
        assertThrows(JellyfishException.class, () -> manager.resolveReference(MODEL + "/"));
    }

    @Test
    void resolveReference_should_throw_when_blank() {
        // When / Then
        assertThrows(JellyfishException.class, () -> newModelManager().resolveReference("  "));
    }

    @Test
    void resolveReference_should_throw_when_model_unknown() {
        // Given
        when(modelRegistry.findProvidersByModelName("ghost")).thenReturn(Collections.<String, Model>emptyMap());

        // When / Then
        assertThrows(JellyfishException.class, () -> newModelManager().resolveReference("ghost"));
    }

    @Test
    void refreshCatalogs_should_replace_models_when_plugin_reports_catalog() {
        // Given：一个由插件接管的 provider
        Provider provider = pluginProvider();
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(llmClientFactory.isBuiltinType("my-type")).thenReturn(false);
        extensions.handle("plugin-a", ModelCatalogRequest.class, PROVIDER, null, request -> ModelCatalogResult.of(
                Arrays.asList(new ModelDescriptor("llama-3", "Llama 3", 8192, 2048))),
                RegisterOptions.DEFAULT);
        ModelManager manager = newModelManager();

        // When
        manager.refreshCatalogs();

        // Then
        ArgumentCaptor<List<Provider>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelRegistry, times(2)).refresh(captor.capture());
        Provider indexed = captor.getValue().get(0);
        assertEquals(1, indexed.getModels().size());
        assertEquals("llama-3", indexed.getModels().get(0).getId());
        assertEquals(8192, indexed.getModels().get(0).getContextLength());
        // 替换走的是副本：配置里那份 provider 对象本身一个字段都没被改
        assertEquals(1, provider.getModels().size());
        assertEquals("gpt-4o-id", provider.getModels().get(0).getId());
    }

    @Test
    void refreshCatalogs_should_carryConfiguredTuning_when_replacing_models() {
        // Given：配置里给这个模型配了采样与直通字段，插件报回来的目录里也有同一个 id
        Provider provider = new Provider(PROVIDER, "my-type", "api-key", "https://api.example.com",
                Arrays.asList(new Model("llama-3", "Llama 3", 128000, 4096, null,
                        new SamplingSettings(0.2d, null, null),
                        Collections.<String, Object>singletonMap("reasoning_effort", "low"))));
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(llmClientFactory.isBuiltinType("my-type")).thenReturn(false);
        extensions.handle("plugin-a", ModelCatalogRequest.class, PROVIDER, null, request -> ModelCatalogResult.of(
                Arrays.asList(new ModelDescriptor("llama-3", "Llama 3", 8192, 2048))),
                RegisterOptions.DEFAULT);
        ModelManager manager = newModelManager();

        // When
        manager.refreshCatalogs();

        // Then：规格以发现结果为准，用户写的调优参数按 id 带过来——
        // 否则一次元数据刷新会把配置里明明还写着的参数悄悄清掉
        ArgumentCaptor<List<Provider>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelRegistry, times(2)).refresh(captor.capture());
        Model indexed = captor.getValue().get(0).getModels().get(0);
        assertEquals(8192, indexed.getContextLength());
        assertEquals(0.2d, indexed.getSampling().getTemperature());
        assertEquals("low", indexed.getVendorBody().get("reasoning_effort"));
    }

    @Test
    void refreshCatalogs_should_carryMaxTokensField_when_replacing_models() {
        // Given：配置里声明了这个模型只认 max_completion_tokens
        Provider provider = new Provider(PROVIDER, "my-type", "api-key", "https://api.example.com",
                Arrays.asList(new Model("llama-3", "Llama 3", 128000, 4096,
                        Model.COMPLETION_MAX_TOKENS_FIELD, null, null)));
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(llmClientFactory.isBuiltinType("my-type")).thenReturn(false);
        extensions.handle("plugin-a", ModelCatalogRequest.class, PROVIDER, null, request -> ModelCatalogResult.of(
                Arrays.asList(new ModelDescriptor("llama-3", "Llama 3", 8192, 2048))),
                RegisterOptions.DEFAULT);
        ModelManager manager = newModelManager();

        // When
        manager.refreshCatalogs();

        // Then：与 sampling / vendorBody 同一口径——发现只换规格，字段名按 id 带过来
        ArgumentCaptor<List<Provider>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelRegistry, times(2)).refresh(captor.capture());
        assertEquals(Model.COMPLETION_MAX_TOKENS_FIELD,
                captor.getValue().get(0).getModels().get(0).getMaxTokensField());
    }

    @Test
    void refreshCatalogs_should_leave_tuningEmpty_when_model_is_not_configured() {
        // Given：目录里的模型配置里没写过（新发现的），它不该凭空继承别人的调优
        Provider provider = pluginProvider();
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(llmClientFactory.isBuiltinType("my-type")).thenReturn(false);
        extensions.handle("plugin-a", ModelCatalogRequest.class, PROVIDER, null, request -> ModelCatalogResult.of(
                Arrays.asList(new ModelDescriptor("brand-new", "Brand New", 8192, 2048))),
                RegisterOptions.DEFAULT);
        ModelManager manager = newModelManager();

        // When
        manager.refreshCatalogs();

        // Then
        ArgumentCaptor<List<Provider>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelRegistry, times(2)).refresh(captor.capture());
        Model indexed = captor.getValue().get(0).getModels().get(0);
        assertTrue(indexed.getSampling().isEmpty());
        assertTrue(indexed.getVendorBody().isEmpty());
    }

    @Test
    void refreshCatalogs_should_keep_configured_models_when_catalog_empty() {
        // 「我不表态」必须回落成配置，而不是把一个本来能用的 provider 清空
        Provider provider = pluginProvider();
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(llmClientFactory.isBuiltinType("my-type")).thenReturn(false);
        extensions.handle("plugin-a", ModelCatalogRequest.class, PROVIDER, null,
                request -> ModelCatalogResult.empty(), RegisterOptions.DEFAULT);
        ModelManager manager = newModelManager();

        manager.refreshCatalogs();

        ArgumentCaptor<List<Provider>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelRegistry, times(2)).refresh(captor.capture());
        assertEquals("gpt-4o-id", captor.getValue().get(0).getModels().get(0).getId());
    }

    @Test
    void refreshCatalogs_should_keep_configured_models_when_handler_fails() {
        // 目录发现失败不该让一个本来可用的 provider 变得不可用
        Provider provider = pluginProvider();
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(llmClientFactory.isBuiltinType("my-type")).thenReturn(false);
        extensions.handle("plugin-a", ModelCatalogRequest.class, PROVIDER, null, request -> {
            throw new IllegalStateException("目录服务挂了");
        }, RegisterOptions.DEFAULT);
        ModelManager manager = newModelManager();

        manager.refreshCatalogs();

        ArgumentCaptor<List<Provider>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelRegistry, times(2)).refresh(captor.capture());
        assertEquals("gpt-4o-id", captor.getValue().get(0).getModels().get(0).getId());
    }

    @Test
    void refreshCatalogs_should_not_ask_plugins_for_builtin_types() {
        // 内核自带的类型有固定的模型来源，问它们只是白跑一趟
        Provider provider = provider();
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(llmClientFactory.isBuiltinType("openai")).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        extensions.handle("plugin-a", ModelCatalogRequest.class, PROVIDER, null, request -> {
            calls.incrementAndGet();
            return ModelCatalogResult.of(Collections.singletonList(ModelDescriptor.of("m")));
        }, RegisterOptions.DEFAULT);
        ModelManager manager = newModelManager();

        manager.refreshCatalogs();

        assertEquals(0, calls.get());
    }

    @Test
    void refreshCatalogs_should_publish_models_loaded_event() {
        when(runtimeConfig.getProviders()).thenReturn(Collections.<Provider>emptyList());
        ModelManager manager = newModelManager();

        manager.refreshCatalogs();

        verify(events).publish(any(ModelsLoadedEvent.class));
    }

    @Test
    void refreshCatalogs_should_tolerate_null_provider_entries() {
        when(runtimeConfig.getProviders()).thenReturn(
                Arrays.asList(provider(), null));
        when(llmClientFactory.isBuiltinType("openai")).thenReturn(true);
        ModelManager manager = newModelManager();

        manager.refreshCatalogs();

        ArgumentCaptor<List<Provider>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelRegistry, times(2)).refresh(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertNull(captor.getValue().get(1));
    }

    @Test
    void refreshCatalogs_should_call_catalog_handler_once_per_provider() {
        Provider provider = pluginProvider();
        when(runtimeConfig.getProviders()).thenReturn(Collections.singletonList(provider));
        when(llmClientFactory.isBuiltinType("my-type")).thenReturn(false);
        AtomicInteger calls = new AtomicInteger();
        extensions.handle("plugin-a", ModelCatalogRequest.class, PROVIDER, null, request -> {
            calls.incrementAndGet();
            assertEquals(PROVIDER, request.getProviderName());
            assertEquals("my-type", request.getProviderType());
            assertEquals(PROVIDER, request.getRouteKey());
            return ModelCatalogResult.of(Collections.singletonList(ModelDescriptor.of("m")));
        }, RegisterOptions.DEFAULT);
        ModelManager manager = newModelManager();

        manager.refreshCatalogs();

        assertEquals(1, calls.get());
    }

    /**
     * 构造由插件接管的 provider（带一份配置里写的模型）。
     *
     * @return provider
     */
    private static Provider pluginProvider() {
        return new Provider(PROVIDER, "my-type", "api-key", "https://api.example.com",
                Arrays.asList(new Model("gpt-4o-id", MODEL, 128000, 4096)));
    }

    /**
     * 构造被测实例，构造器会先执行一次索引刷新（不广播事件）。
     *
     * @return ModelManager 实例
     */
    private ModelManager newModelManager() {
        return new ModelManager(runtimeConfig, modelRegistry, llmClientFactory, events, extensions);
    }

    /**
     * 构造带一个模型的 provider。
     *
     * @return provider
     */
    private static Provider provider() {
        return new Provider(PROVIDER, "openai", "api-key", "https://api.example.com",
                Arrays.asList(new Model("gpt-4o-id", MODEL, 128000, 4096)));
    }
}
