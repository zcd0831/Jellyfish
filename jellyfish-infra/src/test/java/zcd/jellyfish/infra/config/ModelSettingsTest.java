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
    void deserialization_should_support_two_providers_sharing_one_endpoint() {
        // Given：同一端点下的两个 provider——一个走厂商默认（DeepSeek V4 默认思考），
        // 一个在 model 级关掉思考并调温度；两边的模型 id 相同、展示名不同
        String json = "{\"defaultProvider\":\"deepseek\",\"defaultModel\":\"deepseek-flash\","
                + "\"providers\":{"
                + "\"deepseek\":{\"type\":\"deepseek\",\"apiKey\":\"k\",\"baseUrl\":\"https://api.deepseek.com\","
                + "\"models\":[{\"id\":\"deepseek-flash\",\"name\":\"deepseek-flash\","
                + "\"contextLength\":1000000,\"maxOutputTokens\":384000}]},"
                + "\"deepseek-fast\":{\"type\":\"deepseek\",\"apiKey\":\"k\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"models\":[{\"id\":\"deepseek-flash\",\"name\":\"deepseek-flash-fast\","
                + "\"contextLength\":1000000,\"maxOutputTokens\":65536,"
                + "\"sampling\":{\"temperature\":0.3,\"topP\":0.95},"
                + "\"vendorBody\":{\"thinking\":{\"type\":\"disabled\"}}}]}}}";

        // When
        ModelSettings settings = ObjectMapperWrapper.readValue(json, ModelSettings.class);

        // Then：两个 provider 各有自己的模型与调优，互不影响
        assertEquals(2, settings.getProviders().size());
        Model fast = settings.getProviders().get("deepseek-fast").getModels().get(0);
        assertEquals(0.3d, fast.getSampling().getTemperature());
        assertEquals(0.95d, fast.getSampling().getTopP());
        assertEquals("disabled", thinkingTypeOf(fast));
        assertNull(settings.getProviders().get("deepseek").getModels().get(0).getSampling().getTemperature());
    }

    @Test
    void deserialization_should_bind_sampling_and_passthrough_sections() {
        // Given：provider 级与 model 级都写出全部新段，且保留键混在里面
        String json = "{\"providers\":{\"openai\":{\"type\":\"openai\","
                + "\"sampling\":{\"temperature\":0.2,\"stop\":[\"</done>\"]},"
                + "\"vendorBody\":{\"service_tier\":\"flex\",\"messages\":\"伪造的历史\"},"
                + "\"vendorHeaders\":{\"x-tenant\":\"t-1\",\"Content-Type\":\"text/plain\"},"
                + "\"models\":[{\"id\":\"gpt-4o\",\"contextLength\":128000,\"maxOutputTokens\":4096,"
                + "\"sampling\":{\"topP\":0.9},\"vendorBody\":{\"reasoning_effort\":\"low\"}}]}}}";

        // When
        ModelSettings settings = ObjectMapperWrapper.readValue(json, ModelSettings.class);

        // Then：合法项生效，保留键在解析期就被丢弃
        Provider provider = settings.getProviders().get("openai");
        assertEquals(0.2d, provider.getSampling().getTemperature());
        assertEquals(Collections.singletonList("</done>"), provider.getSampling().getStop());
        assertEquals("flex", provider.getVendorBody().get("service_tier"));
        assertFalse(provider.getVendorBody().containsKey("messages"));
        assertEquals(Collections.singletonMap("x-tenant", "t-1"), provider.getVendorHeaders());
        assertEquals(0.9d, provider.getModels().get(0).getSampling().getTopP());
        assertEquals("low", provider.getModels().get(0).getVendorBody().get("reasoning_effort"));
    }

    @Test
    void deserialization_should_bind_maxTokensField_for_reasoning_models() {
        // Given：OpenAI 的推理模型与 gpt-5 之后只认 max_completion_tokens，写错字段名就是 400
        String json = "{\"providers\":{\"openai\":{\"type\":\"openai\","
                + "\"models\":[{\"id\":\"gpt-5\",\"contextLength\":400000,\"maxOutputTokens\":8192,"
                + "\"maxTokensField\":\"max_completion_tokens\"},"
                + "{\"id\":\"gpt-4o\",\"contextLength\":128000,\"maxOutputTokens\":4096}]}}}";

        // When
        ModelSettings settings = ObjectMapperWrapper.readValue(json, ModelSettings.class);

        // Then
        Provider provider = settings.getProviders().get("openai");
        assertEquals("max_completion_tokens", provider.getModels().get(0).getMaxTokensField());
        // 不写就是 null，表示交给客户端用缺省拼法（max_tokens）——DeepSeek 这类端点认的是它
        assertNull(provider.getModels().get(1).getMaxTokensField());
    }

    @Test
    void deserialization_should_fall_back_when_maxTokensField_is_unsupported() {
        // Given：一个内核不知道怎么下发的字段名（写错既不会命中厂商字段，也没有任何提示）
        String json = "{\"providers\":{\"openai\":{\"type\":\"openai\","
                + "\"models\":[{\"id\":\"gpt-5\",\"contextLength\":1000,\"maxOutputTokens\":100,"
                + "\"maxTokensField\":\"max_output_tokens\"}]}}}";

        // When
        ModelSettings settings = ObjectMapperWrapper.readValue(json, ModelSettings.class);

        // Then：回退到缺省，而不是把错名字原样发出去
        assertNull(settings.getProviders().get("openai").getModels().get(0).getMaxTokensField());
    }

    /**
     * 取出模型直通字段里 {@code thinking.type} 的值。
     *
     * @param model 模型定义
     * @return {@code thinking.type} 的值
     */
    @SuppressWarnings("unchecked")
    private static String thinkingTypeOf(Model model) {
        Map<String, Object> thinking = (Map<String, Object>) model.getVendorBody().get("thinking");
        return (String) thinking.get("type");
    }
}
