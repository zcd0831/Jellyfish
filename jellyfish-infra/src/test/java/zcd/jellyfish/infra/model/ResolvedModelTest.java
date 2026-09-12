package zcd.jellyfish.infra.model;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResolvedModel} 的单元测试：验证取值、命名以及按「provider 名 + model 名」比较的语义。
 *
 * @author zcd
 */
class ResolvedModelTest {

    @Test
    void constructor_should_throw_when_provider_or_model_null() {
        // Given
        Provider provider = provider("openai", "gpt-4o");
        Model model = provider.getModels().get(0);

        // When / Then
        assertThrows(JellyfishException.class, () -> new ResolvedModel(null, model));
        assertThrows(JellyfishException.class, () -> new ResolvedModel(provider, null));
    }

    @Test
    void getters_should_return_resolved_provider_and_model() {
        // Given
        Provider provider = provider("openai", "gpt-4o");
        Model model = provider.getModels().get(0);

        // When
        ResolvedModel resolved = new ResolvedModel(provider, model);

        // Then
        assertSame(provider, resolved.getProvider());
        assertSame(model, resolved.getModel());
        assertEquals("openai", resolved.getProviderName());
        assertEquals("gpt-4o", resolved.getModelName());
        assertEquals("openai/gpt-4o", resolved.toString());
    }

    @Test
    void equals_should_compare_by_provider_and_model_name() {
        // Given
        Provider providerA = provider("openai", "gpt-4o");
        Provider providerB = provider("openai", "gpt-4o");
        Provider providerOtherModel = provider("openai", "gpt-4o-mini");
        ResolvedModel left = new ResolvedModel(providerA, providerA.getModels().get(0));
        ResolvedModel same = new ResolvedModel(providerB, providerB.getModels().get(0));
        ResolvedModel otherModel = new ResolvedModel(providerOtherModel, providerOtherModel.getModels().get(0));

        // When / Then
        assertEquals(left, same);
        assertEquals(left.hashCode(), same.hashCode());
        assertNotEquals(left, otherModel);
        assertTrue(left.equals(left));
        assertFalse(left.equals(null));
        assertFalse(left.equals("openai/gpt-4o"));
    }

    /**
     * 构造带单个模型的 provider。
     *
     * @param providerName provider 名
     * @param modelName    model 名
     * @return provider
     */
    private static Provider provider(String providerName, String modelName) {
        return new Provider(providerName, "openai", "api-key", "https://api.example.com",
                Arrays.asList(new Model(modelName + "-id", modelName, 128000, 4096)));
    }
}
