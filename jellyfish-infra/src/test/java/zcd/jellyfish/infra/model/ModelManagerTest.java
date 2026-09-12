package zcd.jellyfish.infra.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmClientFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * 构造被测实例，构造器会先执行一次索引刷新。
     *
     * @return ModelManager 实例
     */
    private ModelManager newModelManager() {
        return new ModelManager(runtimeConfig, modelRegistry, llmClientFactory);
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
