package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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

    @Test
    void constructor_should_tolerate_null_creators_when_injected_map_is_null() {
        // When
        LlmClientFactory factory = new LlmClientFactory(null);

        // Then
        assertFalse(factory.supports(TYPE_OPENAI));
        assertTrue(factory.getRegisteredTypes().isEmpty());
    }

    @Test
    void supports_should_normalize_type_when_looking_up() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(
                creatorMap(provider -> mock(LlmClient.class)));

        // Then
        assertTrue(factory.supports(TYPE_OPENAI));
        assertTrue(factory.supports(" OPENAI "));
        assertFalse(factory.supports("unknown"));
        assertFalse(factory.supports(null));
    }

    @Test
    void getClient_should_throw_when_provider_is_null() {
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)));

        assertThrows(JellyfishException.class, () -> factory.getClient(null));
    }

    @Test
    void getClient_should_throw_when_provider_type_is_blank() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)));
        Provider provider = new Provider("test", "   ", "key", null, Collections.emptyList());

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.getClient(provider));
    }

    @Test
    void getClient_should_throw_when_provider_type_is_not_registered() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)));
        Provider provider = new Provider("test", "gemini", "key", null, Collections.emptyList());

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.getClient(provider));
    }

    @Test
    void getClient_should_throw_when_creator_returns_null() {
        // Given
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> null));

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
        }));
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
        }));

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
        }));
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
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)));

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
        }));
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
        LlmClientFactory factory = new LlmClientFactory(creators);

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
        LlmClientFactory factory = new LlmClientFactory(creatorMap(provider -> mock(LlmClient.class)));
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
