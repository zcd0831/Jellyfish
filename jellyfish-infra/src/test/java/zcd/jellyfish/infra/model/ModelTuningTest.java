package zcd.jellyfish.infra.model;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ProviderCacheSettings;
import zcd.jellyfish.infra.config.SamplingSettings;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelTuning} 的单元测试：provider 基线与 model 覆盖的合成规则。
 *
 * @author zcd
 */
class ModelTuningTest {

    @Test
    void of_should_throw_when_provider_or_model_is_null() {
        // Given
        Provider provider = provider(new SamplingSettings(), Collections.<String, Object>emptyMap(),
                Collections.<String, String>emptyMap());
        Model model = new Model("gpt-4o", "gpt-4o", 1000, 100);

        // When / Then
        assertThrows(JellyfishException.class, () -> ModelTuning.of(null, model));
        assertThrows(JellyfishException.class, () -> ModelTuning.of(provider, null));
    }

    @Test
    void of_should_override_sampling_field_by_field() {
        // Given：provider 给基线，model 只改温度
        Provider provider = provider(new SamplingSettings(0.2d, 0.9d, null),
                Collections.<String, Object>emptyMap(), Collections.<String, String>emptyMap());
        Model model = new Model("gpt-4o", "gpt-4o", 1000, 100, null,
                new SamplingSettings(1d, null, null), null);

        // When
        ModelTuning tuning = ModelTuning.of(provider, model);

        // Then
        assertEquals(1d, tuning.getSampling().getTemperature());
        assertEquals(0.9d, tuning.getSampling().getTopP());
    }

    @Test
    void of_should_deep_merge_extra_body_from_model_over_provider() {
        // Given：两侧都写 generationConfig，model 级只加一处生成参数
        // （temperature 这类采样键在这里是保留键，只能走 sampling 段，因此用别的嵌套键）
        Map<String, Object> providerBody = new LinkedHashMap<String, Object>();
        providerBody.put("generationConfig", new LinkedHashMap<String, Object>(
                Collections.<String, Object>singletonMap("responseMimeType", "application/json")));
        providerBody.put("service_tier", "flex");
        Map<String, Object> modelBody = new LinkedHashMap<String, Object>();
        Map<String, Object> modelConfig = new LinkedHashMap<String, Object>();
        modelConfig.put("thinkingConfig", Collections.<String, Object>singletonMap("thinkingBudget", 1024));
        modelBody.put("generationConfig", modelConfig);
        Model model = new Model("gpt-4o", "gpt-4o", 1000, 100, null, null, modelBody);
        Provider provider = provider(new SamplingSettings(), providerBody,
                Collections.singletonMap("x-tenant", "t-1"));

        // When
        ModelTuning tuning = ModelTuning.of(provider, model);

        // Then：深合并保住了 provider 级那一项，同时带上 model 级的
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) tuning.getVendorBody().get("generationConfig");
        assertEquals("application/json", config.get("responseMimeType"));
        assertTrue(config.containsKey("thinkingConfig"));
        assertEquals("flex", tuning.getVendorBody().get("service_tier"));
        // 请求头只有 provider 级，原样沿用
        assertEquals("t-1", tuning.getVendorHeaders().get("x-tenant"));
    }

    @Test
    void isEmpty_should_be_true_when_nothing_is_configured() {
        // Given：一个什么都不配的 provider 与 model
        Provider provider = provider(new SamplingSettings(), Collections.<String, Object>emptyMap(),
                Collections.<String, String>emptyMap());
        Model model = new Model("gpt-4o", "gpt-4o", 1000, 100);

        // When
        ModelTuning tuning = ModelTuning.of(provider, model);

        // Then
        assertTrue(tuning.isEmpty());
    }

    @Test
    void applyTo_should_set_maxTokensField_on_builder() {
        // Given：一个只认 max_completion_tokens 的模型
        Provider provider = provider(new SamplingSettings(), Collections.<String, Object>emptyMap(),
                Collections.<String, String>emptyMap());
        Model model = new Model("gpt-5", "gpt-5", 1000, 100,
                Model.COMPLETION_MAX_TOKENS_FIELD, null, null);
        LlmRequest.Builder builder = LlmRequest.builder("gpt-5").message(LlmMessage.user("你好"));

        // When
        ModelTuning.of(provider, model).applyTo(builder);

        // Then：字段名与 maxTokens 一起在这里落——压缩回退路径曾因为自己写 maxTokens 而漏了它
        assertEquals(Model.COMPLETION_MAX_TOKENS_FIELD, builder.build().getMaxTokensField());
    }

    @Test
    void isEmpty_should_be_false_when_only_maxTokensField_is_set() {
        // Given
        Provider provider = provider(new SamplingSettings(), Collections.<String, Object>emptyMap(),
                Collections.<String, String>emptyMap());
        Model model = new Model("gpt-5", "gpt-5", 1000, 100,
                Model.COMPLETION_MAX_TOKENS_FIELD, null, null);

        // When / Then：它也是调优内容的一部分，不能被当成「什么都没配」
        assertFalse(ModelTuning.of(provider, model).isEmpty());
    }

    @Test
    void applyTo_should_set_sampling_and_vendorBody_on_builder() {
        // Given：provider 配了温度与直通字段，model 覆盖温度
        Provider provider = provider(new SamplingSettings(0.2d, 0.9d, null),
                Collections.<String, Object>singletonMap("service_tier", "flex"),
                Collections.<String, String>emptyMap());
        Model model = new Model("gpt-4o", "gpt-4o", 1000, 100, null,
                new SamplingSettings(1d, null, null), null);
        LlmRequest.Builder builder = LlmRequest.builder("gpt-4o").message(LlmMessage.user("你好"));

        // When
        ModelTuning.of(provider, model).applyTo(builder);

        // Then：规则只有这一处——请求的三个来源（正常组装、fork、回退）都调它，不会各落一套
        LlmRequest request = builder.build();
        assertEquals(1d, request.getTemperature());
        assertEquals(0.9d, request.getTopP());
        assertEquals("flex", request.getVendorBody().get("service_tier"));
    }

    @Test
    void applyTo_should_leave_request_untouched_when_nothing_configured() {
        // Given
        Provider provider = provider(new SamplingSettings(), Collections.<String, Object>emptyMap(),
                Collections.<String, String>emptyMap());
        Model model = new Model("gpt-4o", "gpt-4o", 1000, 100);
        LlmRequest.Builder builder = LlmRequest.builder("gpt-4o").message(LlmMessage.user("你好"));

        // When
        ModelTuning.of(provider, model).applyTo(builder);

        // Then：未表态就不下发，而不是下发一个「内核猜的缺省值」
        LlmRequest request = builder.build();
        assertNull(request.getTemperature());
        assertNull(request.getTopP());
        assertTrue(request.getStop().isEmpty());
        assertTrue(request.getVendorBody().isEmpty());
    }

    /**
     * 构造测试用 provider。
     *
     * @param sampling     采样参数
     * @param vendorBody    直通请求体字段
     * @param vendorHeaders 直通请求头
     * @return provider
     */
    private static Provider provider(SamplingSettings sampling, Map<String, Object> vendorBody,
                                     Map<String, String> vendorHeaders) {
        return new Provider("openai", "openai", "key", "https://api.openai.com",
                Arrays.asList(new Model("gpt-4o", "gpt-4o", 1000, 100)), new ProviderCacheSettings(),
                sampling, vendorBody, vendorHeaders);
    }
}
