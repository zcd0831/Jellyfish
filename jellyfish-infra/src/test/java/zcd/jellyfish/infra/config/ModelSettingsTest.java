package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ModelSettings} 的单元测试：验证缺省值与不可变行为。
 *
 * @author zcd
 */
class ModelSettingsTest {

    @Test
    void getDefaultProvider_should_return_null_when_not_set() {
        // Given / When
        ModelSettings settings = new ModelSettings(null, null, null);

        // Then
        assertNull(settings.getDefaultProvider());
    }

    @Test
    void getDefaultModel_should_return_null_when_not_set() {
        // Given / When
        ModelSettings settings = new ModelSettings(null, null, null);

        // Then
        assertNull(settings.getDefaultModel());
    }

    @Test
    void getProviders_should_return_empty_map_when_not_set() {
        // Given / When
        ModelSettings settings = new ModelSettings(null, null, null);

        // Then
        assertEquals(Collections.emptyMap(), settings.getProviders());
    }

    @Test
    void getProviders_should_return_given_providers() {
        // Given
        Provider provider = new Provider("openai", "openai", null, null, null);

        // When
        ModelSettings settings = new ModelSettings("openai", "gpt-4o",
                Collections.singletonMap("openai", provider));

        // Then
        assertEquals("openai", settings.getDefaultProvider());
        assertEquals("gpt-4o", settings.getDefaultModel());
        assertEquals(provider, settings.getProviders().get("openai"));
    }

    @Test
    void getProviders_should_return_unmodifiable_map() {
        // Given
        ModelSettings settings = new ModelSettings(null, null,
                Collections.singletonMap("openai", new Provider("openai", "openai", null, null, null)));

        // When / Then
        Map<String, Provider> providers = settings.getProviders();
        assertThrows(UnsupportedOperationException.class,
                () -> providers.put("other", new Provider("other", "openai", null, null, null)));
    }

    @Test
    void deserialization_should_bind_providers_and_models() {
        // Given
        String json = "{\"defaultProvider\":\"openai\",\"defaultModel\":\"gpt-4o\","
                + "\"providers\":{\"openai\":{\"type\":\"openai\",\"baseUrl\":\"https://api.openai.com\","
                + "\"models\":[{\"id\":\"gpt-4o\",\"name\":\"gpt-4o\",\"contextLength\":128000}]}}}";

        // When
        ModelSettings settings = ObjectMapperWrapper.readValue(json, ModelSettings.class);

        // Then
        assertEquals("openai", settings.getDefaultProvider());
        Provider provider = settings.getProviders().get("openai");
        assertEquals("openai", provider.getType());
        assertEquals("gpt-4o", provider.getModels().get(0).getId());
        assertEquals(128000, provider.getModels().get(0).getContextLength());
    }

    @Test
    void deserialization_should_bind_sampling_and_passthrough_sections() {
        // Given：provider 级与 model 级都写出全部新段，且保留键混在里面
        String json = "{\"providers\":{\"openai\":{\"type\":\"openai\","
                + "\"sampling\":{\"temperature\":0.2,\"stop\":[\"</done>\"]},"
                + "\"extraBody\":{\"service_tier\":\"flex\",\"messages\":\"伪造的历史\"},"
                + "\"extraHeaders\":{\"x-tenant\":\"t-1\",\"Content-Type\":\"text/plain\"},"
                + "\"models\":[{\"id\":\"gpt-4o\",\"contextLength\":128000,\"maxOutputTokens\":4096,"
                + "\"sampling\":{\"topP\":0.9},\"extraBody\":{\"reasoning_effort\":\"low\"}}]}}}";

        // When
        ModelSettings settings = ObjectMapperWrapper.readValue(json, ModelSettings.class);

        // Then：合法项生效，保留键在解析期就被丢弃
        Provider provider = settings.getProviders().get("openai");
        assertEquals(0.2d, provider.getSampling().getTemperature());
        assertEquals(Collections.singletonList("</done>"), provider.getSampling().getStop());
        assertEquals("flex", provider.getExtraBody().get("service_tier"));
        assertFalse(provider.getExtraBody().containsKey("messages"));
        assertEquals(Collections.singletonMap("x-tenant", "t-1"), provider.getExtraHeaders());
        assertEquals(0.9d, provider.getModels().get(0).getSampling().getTopP());
        assertEquals("low", provider.getModels().get(0).getExtraBody().get("reasoning_effort"));
    }
}
