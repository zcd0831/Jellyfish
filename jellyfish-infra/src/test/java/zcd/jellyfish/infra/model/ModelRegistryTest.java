package zcd.jellyfish.infra.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelRegistry} 的单元测试：验证「整体重建索引」「非法条目过滤」
 * 「按名字解析 provider / model」「按 model 名反查 provider」四类行为。
 *
 * @author zcd
 */
class ModelRegistryTest {

    /** provider 名。 */
    private static final String PROVIDER = "openai";

    /** model 名。 */
    private static final String MODEL = "gpt-4o";

    /** 被测索引。 */
    private ModelRegistry registry;

    /**
     * 每个用例使用全新的空索引。
     */
    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
    }

    @Test
    void refresh_should_index_provider_and_model() {
        // Given
        Provider provider = provider(PROVIDER, MODEL);

        // When
        registry.refresh(Collections.singletonList(provider));

        // Then
        assertSame(provider, registry.findProvider(PROVIDER));
        assertSame(provider.getModels().get(0), registry.findModel(PROVIDER, MODEL));
        assertSame(provider.getModels().get(0), registry.firstModel(PROVIDER));
        assertEquals(Collections.singletonList(provider), registry.getProviders());
    }

    @Test
    void refresh_should_clear_index_when_providers_null() {
        // Given
        registry.refresh(Collections.singletonList(provider(PROVIDER, MODEL)));

        // When
        registry.refresh(null);

        // Then
        assertTrue(registry.getProviders().isEmpty());
        assertNull(registry.findProvider(PROVIDER));
        assertTrue(registry.findProvidersByModelName(MODEL).isEmpty());
    }

    @Test
    void refresh_should_skip_null_and_blank_named_providers() {
        // Given
        Provider blankName = provider("  ", MODEL);
        Provider nullName = provider(null, MODEL);

        // When
        registry.refresh(Arrays.asList(null, blankName, nullName));

        // Then
        assertTrue(registry.getProviders().isEmpty());
        assertTrue(registry.findProvidersByModelName(MODEL).isEmpty());
        assertNull(registry.findProvider("  "));
    }

    @Test
    void refresh_should_skip_null_and_blank_named_models() {
        // Given
        Model valid = new Model("gpt-4o-id", MODEL, 128000, 4096);
        Provider provider = new Provider(PROVIDER, "openai", "api-key", "https://api.example.com",
                Arrays.asList(null, new Model("id", null, 1, 1), new Model("id", "  ", 1, 1), valid));

        // When
        registry.refresh(Collections.singletonList(provider));

        // Then
        assertEquals(Collections.singletonMap(PROVIDER, valid), registry.findProvidersByModelName(MODEL));
        assertTrue(registry.findProvidersByModelName("  ").isEmpty());
        assertNull(registry.findModel(PROVIDER, null));
    }

    @Test
    void refresh_should_keep_provider_without_model_indexed_by_name_only() {
        // Given
        Provider provider = provider(PROVIDER, null);

        // When
        registry.refresh(Collections.singletonList(provider));

        // Then
        assertSame(provider, registry.findProvider(PROVIDER));
        assertNull(registry.firstModel(PROVIDER));
        assertNull(registry.findModel(PROVIDER, MODEL));
        assertTrue(registry.findProvidersByModelName(MODEL).isEmpty());
    }

    @Test
    void refresh_should_rebuild_index_without_stale_entries() {
        // Given
        registry.refresh(Collections.singletonList(provider("openai", "gpt-4o")));

        // When
        Provider replacement = provider("anthropic", "claude-3");
        registry.refresh(Collections.singletonList(replacement));

        // Then
        assertNull(registry.findProvider("openai"));
        assertTrue(registry.findProvidersByModelName("gpt-4o").isEmpty());
        assertSame(replacement, registry.findProvider("anthropic"));
    }

    @Test
    void refresh_should_keep_last_provider_when_name_duplicated() {
        // Given
        Provider first = provider(PROVIDER, "gpt-4o");
        Provider second = provider(PROVIDER, "gpt-4o-mini");

        // When
        registry.refresh(Arrays.asList(first, second));

        // Then
        assertEquals(1, registry.getProviders().size());
        assertSame(second, registry.findProvider(PROVIDER));
        assertSame(second.getModels().get(0), registry.firstModel(PROVIDER));
    }

    @Test
    void findProvidersByModelName_should_return_all_providers_in_config_order() {
        // Given
        Provider openai = provider("openai", MODEL);
        Provider azure = provider("azure", MODEL);
        registry.refresh(Arrays.asList(openai, azure));

        // When
        Map<String, Model> matches = registry.findProvidersByModelName(MODEL);

        // Then
        assertEquals(Arrays.asList("openai", "azure"), new ArrayList<>(matches.keySet()));
        assertSame(openai.getModels().get(0), matches.get("openai"));
        assertSame(azure.getModels().get(0), matches.get("azure"));
    }

    @Test
    void findProvidersByModelName_should_return_empty_map_when_model_name_blank() {
        // Given
        registry.refresh(Collections.singletonList(provider(PROVIDER, MODEL)));

        // When / Then
        assertTrue(registry.findProvidersByModelName(null).isEmpty());
        assertTrue(registry.findProvidersByModelName("  ").isEmpty());
        assertTrue(registry.findProvidersByModelName("missing").isEmpty());
    }

    @Test
    void findProvider_should_return_null_when_name_blank_or_missing() {
        // Given
        registry.refresh(Collections.singletonList(provider(PROVIDER, MODEL)));

        // When / Then
        assertNull(registry.findProvider(null));
        assertNull(registry.findProvider("  "));
        assertNull(registry.findProvider("missing"));
    }

    @Test
    void findModel_should_return_null_when_provider_or_model_missing() {
        // Given
        registry.refresh(Collections.singletonList(provider(PROVIDER, MODEL)));

        // When / Then
        assertNull(registry.findModel("missing", MODEL));
        assertNull(registry.findModel(PROVIDER, "missing"));
    }

    @Test
    void firstModel_should_return_null_when_provider_missing() {
        // When / Then
        assertNull(registry.firstModel("missing"));
        assertNull(registry.firstModel(null));
    }

    @Test
    void findModel_should_tolerate_null_models_returned_by_provider() {
        // Given：Provider 约定 models 为空列表而非 null，这里覆写以验证索引对非常规实现的容错
        Provider provider = providerWithNullModels();

        // When
        registry.refresh(Collections.singletonList(provider));

        // Then
        assertSame(provider, registry.findProvider(PROVIDER));
        assertNull(registry.findModel(PROVIDER, MODEL));
        assertNull(registry.firstModel(PROVIDER));
        assertTrue(registry.findProvidersByModelName(MODEL).isEmpty());
    }

    @Test
    void getProviders_should_return_copy_that_does_not_mutate_index() {
        // Given
        Provider provider = provider(PROVIDER, MODEL);
        registry.refresh(Collections.singletonList(provider));

        // When
        List<Provider> providers = registry.getProviders();
        providers.clear();

        // Then
        assertEquals(Collections.singletonList(provider), registry.getProviders());
    }

    /**
     * 构造带单个模型的 provider。
     *
     * @param providerName provider 名
     * @param modelName    model 名，为 {@code null} 时该 provider 不含任何模型
     * @return provider
     */
    private static Provider provider(String providerName, String modelName) {
        List<Model> models = modelName == null
                ? Collections.<Model>emptyList()
                : Collections.singletonList(new Model(modelName + "-id", modelName, 128000, 4096));
        return new Provider(providerName, "openai", "api-key", "https://api.example.com", models);
    }

    /**
     * 构造一个「models 返回 null」的 provider，用于验证索引对不遵守空列表约定的实现仍能容错。
     *
     * @return provider
     */
    private static Provider providerWithNullModels() {
        return new Provider(PROVIDER, "openai", "api-key", "https://api.example.com", null) {
            @Override
            public List<Model> getModels() {
                return null;
            }
        };
    }
}
