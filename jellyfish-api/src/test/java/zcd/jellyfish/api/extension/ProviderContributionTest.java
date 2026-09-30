package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.llm.LlmTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 厂商可插拔四个类型（注册请求 / 贡献 / 目录请求 / 目录结果 / 模型描述）的单元测试。
 *
 * @author zcd
 */
class ProviderContributionTest {

    @Test
    void unsupported_should_carry_no_transport_and_no_display_name() {
        ProviderContribution contribution = ProviderContribution.unsupported();

        assertFalse(contribution.isSupported());
        assertNull(contribution.getTransport());
        assertNull(contribution.getDisplayName());
    }

    @Test
    void of_should_carry_transport_and_display_name() {
        LlmTransport transport = (request, listener) -> { };
        ProviderContribution contribution = ProviderContribution.of("本地推理", transport);

        assertTrue(contribution.isSupported());
        assertSame(transport, contribution.getTransport());
        assertEquals("本地推理", contribution.getDisplayName());
    }

    @Test
    void of_should_reject_blank_display_name() {
        assertThrows(JellyfishException.class,
                () -> ProviderContribution.of(" ", (request, listener) -> { }));
    }

    @Test
    void of_should_reject_null_transport() {
        assertThrows(JellyfishException.class, () -> ProviderContribution.of("插件", null));
    }

    @Test
    void registration_request_should_route_by_provider_type() {
        ProviderRegistrationRequest request = new ProviderRegistrationRequest("my-type");

        assertEquals("my-type", request.getRouteKey());
        assertEquals("my-type", request.getProviderType());
        assertEquals(ProviderContribution.class, request.getResultType());
        // 进程级请求：没有会话归属
        assertNull(request.getSessionId());
    }

    @Test
    void catalog_request_should_route_by_provider_name() {
        ModelCatalogRequest request = new ModelCatalogRequest("local", "my-type");

        assertEquals("local", request.getRouteKey());
        assertEquals("local", request.getProviderName());
        assertEquals("my-type", request.getProviderType());
        assertEquals(ModelCatalogResult.class, request.getResultType());
        assertNull(request.getSessionId());
    }

    @Test
    void catalog_result_should_report_presence_only_when_models_present() {
        assertFalse(ModelCatalogResult.empty().isPresent());
        assertTrue(ModelCatalogResult.of(Collections.singletonList(ModelDescriptor.of("m"))).isPresent());
        // 「一个模型都没有」与「我不表态」无法区分，交给内核按安全的那一侧处理
        assertFalse(ModelCatalogResult.of(new ArrayList<ModelDescriptor>()).isPresent());
        assertTrue(ModelCatalogResult.of(null).getModels().isEmpty());
    }

    @Test
    void catalog_result_should_copy_models() {
        List<ModelDescriptor> models = new ArrayList<ModelDescriptor>();
        models.add(ModelDescriptor.of("m"));
        ModelCatalogResult result = ModelCatalogResult.of(models);

        models.clear();

        assertEquals(1, result.getModels().size());
        assertThrows(UnsupportedOperationException.class, () -> result.getModels().add(ModelDescriptor.of("n")));
    }

    @Test
    void model_descriptor_should_default_name_to_id() {
        ModelDescriptor descriptor = new ModelDescriptor("llama-3", null, 8192, 2048);

        assertEquals("llama-3", descriptor.getId());
        assertEquals("llama-3", descriptor.getName());
        assertEquals(8192, descriptor.getContextLength());
        assertEquals(2048, descriptor.getMaxOutputTokens());
    }

    @Test
    void model_descriptor_should_treat_unknown_specs_as_zero() {
        // 0 的含义是「不知道」：内核据此不按窗口裁剪历史，而不是把窗口当成 0
        ModelDescriptor descriptor = new ModelDescriptor("m", null, -1, -5);

        assertEquals(0, descriptor.getContextLength());
        assertEquals(0, descriptor.getMaxOutputTokens());
    }

    @Test
    void model_descriptor_should_reject_blank_id() {
        assertThrows(JellyfishException.class, () -> ModelDescriptor.of(" "));
    }
}
