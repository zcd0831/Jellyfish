package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ProviderContribution;
import zcd.jellyfish.api.extension.ProviderRegistrationRequest;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.llm.LlmTransportResponse;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link LlmClientFactory} 的单元测试：覆盖注册、缓存、失效与容量淘汰。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class LlmClientFactoryTest {

    /** 测试使用的 provider type。 */
    private static final String TYPE_OPENAI = "openai";

    /** 空的扩展注册表：这些用例只验证内核自带类型。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(new TypeRegistry());

    /** 流式线程池，测试里同步执行即可。 */
    private final ExecutorService streamExecutor = new DirectExecutorService();

    @Test
    void constructor_should_tolerate_null_creators_when_injected_map_is_null() {
        // When
        LlmClientFactory factory = new LlmClientFactory(null, extensions, streamExecutor);

        // Then
        assertFalse(factory.supports(TYPE_OPENAI));
        assertTrue(factory.getRegisteredTypes().isEmpty());
    }

    @Test
    void supports_should_normalize_type_when_looking_up() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(
                creatorMap(provider -> mock(LlmClient.class)), extensions, streamExecutor);

        // Then
        assertTrue(factory.supports(TYPE_OPENAI));
        assertTrue(factory.supports(" OPENAI "));
        assertFalse(factory.supports("unknown"));
        assertFalse(factory.supports(null));
    }

    @Test
    void getClient_should_throw_when_provider_is_null() {
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)), extensions, streamExecutor);

        assertThrows(JellyfishException.class, () -> factory.getClient(null));
    }

    @Test
    void getClient_should_throw_when_provider_type_is_blank() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)), extensions, streamExecutor);
        Provider provider = new Provider("test", "   ", "key", null, Collections.emptyList());

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.getClient(provider));
    }

    @Test
    void getClient_should_throw_when_provider_type_is_not_registered() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)), extensions, streamExecutor);
        Provider provider = new Provider("test", "gemini", "key", null, Collections.emptyList());

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.getClient(provider));
    }

    @Test
    void getClient_should_throw_when_creator_returns_null() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> null), extensions, streamExecutor);

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.getClient(provider("key-1")));
    }

    @Test
    void getClient_should_reuse_instance_when_signature_is_unchanged() {
        // Given
        AtomicInteger created = new AtomicInteger();
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> {
            created.incrementAndGet();
            return mock(LlmClient.class);
        }), extensions, streamExecutor);
        Provider provider = provider("key-1");

        // When
        LlmClient first = factory.getClient(provider);
        LlmClient second = factory.getClient(provider);

        // Then
        assertSame(first, second);
        assertEquals(1, created.get());
    }

    @Test
    void getClient_should_create_new_instance_when_api_key_changes() {
        // Given
        AtomicInteger created = new AtomicInteger();
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> {
            created.incrementAndGet();
            return mock(LlmClient.class);
        }), extensions, streamExecutor);

        // When
        LlmClient first = factory.getClient(provider("key-1"));
        LlmClient second = factory.getClient(provider("key-2"));

        // Then
        assertNotSame(first, second);
        assertEquals(2, created.get());
    }

    @Test
    void invalidate_should_drop_cached_client_when_called() {
        // Given
        AtomicInteger created = new AtomicInteger();
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> {
            created.incrementAndGet();
            return mock(LlmClient.class);
        }), extensions, streamExecutor);
        Provider provider = provider("key-1");
        LlmClient first = factory.getClient(provider);

        // When
        factory.invalidate(provider);
        LlmClient second = factory.getClient(provider);

        // Then
        assertNotSame(first, second);
        assertEquals(2, created.get());
    }

    @Test
    void invalidate_should_do_nothing_when_provider_is_null() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)), extensions, streamExecutor);

        // When / Then
        factory.invalidate(null);
        assertTrue(factory.supports(TYPE_OPENAI));
    }

    @Test
    void clearCache_should_drop_all_cached_clients_when_called() {
        // Given
        AtomicInteger created = new AtomicInteger();
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> {
            created.incrementAndGet();
            return mock(LlmClient.class);
        }), extensions, streamExecutor);
        Provider provider = provider("key-1");
        LlmClient first = factory.getClient(provider);

        // When
        factory.clearCache();
        LlmClient second = factory.getClient(provider);

        // Then
        assertNotSame(first, second);
        assertEquals(2, created.get());
    }

    @Test
    void getRegisteredTypes_should_return_unmodifiable_snapshot() {
        // Given
        Map<String, LlmClientCreator> creators = creatorMap(provider -> mock(LlmClient.class));
        LlmClientFactory factory = new LlmClientFactory(creators, extensions, streamExecutor);

        // When
        creators.clear();

        // Then
        assertTrue(factory.supports(TYPE_OPENAI));
        assertThrows(UnsupportedOperationException.class,
                () -> factory.getRegisteredTypes().put("x", provider -> mock(LlmClient.class)));
    }

    @Test
    void getClient_should_evict_eldest_when_cache_exceeds_limit() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)), extensions, streamExecutor);
        Provider first = provider("key-0");
        LlmClient firstClient = factory.getClient(first);
        for (int i = 1; i <= 140; i++) {
            factory.getClient(provider("key-" + i));
        }

        // When
        LlmClient recreated = factory.getClient(first);

        // Then
        assertNotSame(firstClient, recreated);
    }

    @Test
    void getClient_should_route_to_plugin_transport_when_type_is_not_builtin() {
        // Given：插件接管了一个内核不认识的新类型
        ProviderContribution contribution = ProviderContribution.of("测试插件",
                (request, listener) -> listener.onComplete(LlmTransportResponse.text("ok")));
        extensions.handle("plugin-a", ProviderRegistrationRequest.class, "my-type", null,
                request -> contribution, RegisterOptions.DEFAULT);
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)),
                extensions, streamExecutor);
        Provider pluginProvider = new Provider("local", "my-type", "key", "http://localhost",
                Collections.emptyList());

        // When
        LlmClient client = factory.getClient(pluginProvider);

        // Then
        assertTrue(client instanceof PluginLlmClientAdapter);
        assertEquals("local", client.getProvider().getName());
        assertTrue(factory.supports("MY-TYPE "));
        assertFalse(factory.isBuiltinType("my-type"));
    }

    @Test
    void getClient_should_prefer_builtin_creator_over_plugin_for_same_type() {
        // 这不是优先级选择：传输请求里带的是已解析好的 apiKey，一个能顶替 openai 的插件
        // 等于把所有用户的密钥转发到自己的服务器上
        AtomicInteger pluginCalls = new AtomicInteger();
        extensions.handle("plugin-a", ProviderRegistrationRequest.class, TYPE_OPENAI, null,
                request -> {
                    pluginCalls.incrementAndGet();
                    return ProviderContribution.of("恶意插件",
                            (transportRequest, listener) -> listener.onComplete(
                                    LlmTransportResponse.text("hijacked")));
                }, RegisterOptions.DEFAULT);
        LlmClient builtin = mock(LlmClient.class);
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> builtin),
                extensions, streamExecutor);

        // When
        LlmClient client = factory.getClient(provider("key"));

        // Then
        assertSame(builtin, client);
        assertEquals(0, pluginCalls.get());
    }

    @Test
    void getClient_should_report_unknown_type_with_actionable_next_step() {
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)),
                extensions, streamExecutor);
        Provider unknown = new Provider("local", "llama.cpp", "key", null, Collections.emptyList());

        JellyfishException error = assertThrows(JellyfishException.class, () -> factory.getClient(unknown));

        assertTrue(error.getMessage().contains("llama.cpp"), error.getMessage());
        assertTrue(error.getMessage().contains(TYPE_OPENAI), error.getMessage());
        assertTrue(error.getMessage().contains("装一个提供该类型的插件"), error.getMessage());
    }

    @Test
    void getClient_should_treat_plugin_exception_as_unknown_type() {
        // 一个插件挂掉不该让整条路由失败，更不该把它的堆栈当成「用户配置错了」抛给用户
        extensions.handle("plugin-a", ProviderRegistrationRequest.class, "my-type", null,
                request -> {
                    throw new IllegalStateException("插件炸了");
                }, RegisterOptions.DEFAULT);
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)),
                extensions, streamExecutor);
        Provider pluginProvider = new Provider("local", "my-type", "key", null, Collections.emptyList());

        JellyfishException error = assertThrows(JellyfishException.class, () -> factory.getClient(pluginProvider));

        assertTrue(error.getMessage().contains("my-type"), error.getMessage());
    }

    @Test
    void getClient_should_treat_unsupported_contribution_as_unknown_type() {
        // 「我在但这个类型不是我的」与「我不在」对路由的含义完全一致
        extensions.handle("plugin-a", ProviderRegistrationRequest.class, "my-type", null,
                request -> ProviderContribution.unsupported(), RegisterOptions.DEFAULT);
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)),
                extensions, streamExecutor);
        Provider pluginProvider = new Provider("local", "my-type", "key", null, Collections.emptyList());

        assertThrows(JellyfishException.class, () -> factory.getClient(pluginProvider));
        assertFalse(factory.supports("my-type"));
    }

    @Test
    void supports_should_fall_back_to_false_after_plugin_unregistered() {
        // 插件停止后注册按 owner 整批回收，类型自动回到「没人接」——fail-closed
        extensions.handle("plugin-a", ProviderRegistrationRequest.class, "my-type", null,
                request -> ProviderContribution.of("测试插件",
                        (transportRequest, listener) -> listener.onComplete(
                                LlmTransportResponse.text("ok"))), RegisterOptions.DEFAULT);
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)),
                extensions, streamExecutor);
        assertTrue(factory.supports("my-type"));

        extensions.unregisterAll("plugin-a");

        assertFalse(factory.supports("my-type"));
    }

    @Test
    void builtinTypes_should_expose_kernel_types_only() {
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)),
                extensions, streamExecutor);

        assertEquals(Collections.singleton(TYPE_OPENAI), factory.builtinTypes());
        assertThrows(UnsupportedOperationException.class, () -> factory.builtinTypes().add("x"));
    }

    /**
     * 构造只注册 openai 的创建器映射。
     *
     * @param creator 创建器
     * @return 创建器映射
     */
    private static Map<String, LlmClientCreator> creatorMap(LlmClientCreator creator) {
        Map<String, LlmClientCreator> creators = new HashMap<>();
        creators.put(TYPE_OPENAI, creator);
        return creators;
    }

    /**
     * 构造指定 apiKey 的 provider。
     *
     * @param apiKey apiKey
     * @return provider
     */
    private static Provider provider(String apiKey) {
        return new Provider("test", TYPE_OPENAI, apiKey, "http://localhost", Collections.emptyList());
    }
}
