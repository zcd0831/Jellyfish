package zcd.jellyfish.infra.llm;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 客户端工厂：按 {@link Provider#getType()} 查找 {@link LlmClientCreator} 并创建/复用客户端。
 * <p>
 * 所有实现通过 Dagger2 以 {@code Map<String, LlmClientCreator>} 的形式注册（见 {@code LlmModule}）。
 * 创建出的客户端按 provider 的配置签名缓存：配置不变时复用同一个客户端（底层共享 OkHttpClient 连接池）；
 * apiKey / baseUrl 变化时会创建新客户端。
 *
 * @author zcd
 */
@Singleton
public class LlmClientFactory {

    /** 缓存客户端数量上限，超出后按 LRU 淘汰，避免配置反复变更时旧签名客户端无限堆积。 */
    private static final int MAX_CACHED_CLIENTS = 128;

    /** provider type（已规范化为小写）到客户端创建器的映射。 */
    private final Map<String, LlmClientCreator> creators = new ConcurrentHashMap<>();

    /** 按 provider 配置签名缓存的客户端实例，线程安全且按 LRU 淘汰。 */
    private final Map<String, LlmClient> clientCache =
            Collections.synchronizedMap(new ClientCache(MAX_CACHED_CLIENTS));

    /**
     * 构造工厂。
     *
     * @param creators Dagger2 注入的 provider type 到创建器的映射，可为 {@code null}
     */
    @Inject
    public LlmClientFactory(Map<String, LlmClientCreator> creators) {
        if (creators != null) {
            this.creators.putAll(creators);
        }
    }

    /**
     * 获取已注册的 provider type 集合的只读快照。
     *
     * @return provider type 到创建器的只读映射
     */
    public Map<String, LlmClientCreator> getRegisteredTypes() {
        return Collections.unmodifiableMap(new HashMap<>(creators));
    }

    /**
     * 判断是否支持指定的 provider type。
     *
     * @param type provider type，忽略大小写与首尾空白
     * @return 已注册对应创建器时返回 {@code true}
     */
    public boolean supports(String type) {
        String normalized = normalize(type);
        return normalized != null && creators.containsKey(normalized);
    }

    /**
     * 获取（或创建并缓存）指定 provider 对应的客户端。
     *
     * @param provider provider 配置
     * @return 对应的客户端实例
     * @throws JellyfishException provider 为空、type 为空、type 未注册或创建失败时抛出
     */
    public LlmClient getClient(Provider provider) {
        if (provider == null) {
            throw new JellyfishException("provider must not be null");
        }
        String type = normalize(provider.getType());
        if (type == null) {
            throw new JellyfishException("provider type must not be blank");
        }
        LlmClientCreator creator = creators.get(type);
        if (creator == null) {
            throw new JellyfishException("unsupported provider type: " + type
                    + ", registered types: " + creators.keySet());
        }
        String cacheKey = cacheKey(provider, type);
        // 锁内完成「查缓存 + 创建 + 回填」：既保证 LRU 访问顺序的线程安全，也避免并发重复创建客户端
        synchronized (clientCache) {
            LlmClient cached = clientCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            LlmClient created = creator.create(provider);
            if (created == null) {
                throw new JellyfishException("failed to create LlmClient for provider: " + provider.getName());
            }
            clientCache.put(cacheKey, created);
            return created;
        }
    }

    /**
     * 清除某个 provider 的缓存客户端（例如配置刷新后）。
     *
     * @param provider 待失效的 provider，为 {@code null} 时不做任何处理
     */
    public void invalidate(Provider provider) {
        if (provider == null) {
            return;
        }
        String type = normalize(provider.getType());
        if (type != null) {
            clientCache.remove(cacheKey(provider, type));
        }
    }

    /**
     * 清空全部缓存客户端，用于配置整体重载后释放旧配置对应的实例。
     */
    public void clearCache() {
        clientCache.clear();
    }

    /**
     * 计算 provider 的缓存签名。type / name / apiKey / baseUrl 任一变化都会产生新签名。
     *
     * @param provider       provider 配置
     * @param normalizedType 已规范化的 provider type
     * @return 缓存签名
     */
    private static String cacheKey(Provider provider, String normalizedType) {
        return normalizedType
                + '|' + nullToEmpty(provider.getName())
                + '|' + nullToEmpty(provider.getApiKey())
                + '|' + nullToEmpty(provider.getBaseUrl());
    }

    /**
     * 规范化 provider type：去除首尾空白并转为小写。
     *
     * @param type 原始 provider type
     * @return 规范化后的 type，为空时返回 {@code null}
     */
    private static String normalize(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 把 {@code null} 字符串归一化为空串，用于拼接缓存签名时避免 {@code null} 文本污染。
     *
     * @param value 原始字符串
     * @return 原字符串，或空串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 有容量上限、按访问顺序淘汰的客户端缓存。
     * <p>
     * 配置反复变更会产生大量不再复用的旧签名，因此需要有界缓存；访问顺序（LRU）由
     * {@link LinkedHashMap} 的 accessOrder 保证，最久未使用的条目在超出容量时被淘汰，
     * 被淘汰后下次使用会重新创建，不影响正确性。
     *
     * @author zcd
     */
    private static final class ClientCache extends LinkedHashMap<String, LlmClient> {

        /** 序列化版本号。 */
        private static final long serialVersionUID = 1L;

        /** 缓存容量上限。 */
        private final int maxSize;

        /**
         * 构造缓存。
         *
         * @param maxSize 容量上限，超出后淘汰最久未使用的条目
         */
        private ClientCache(int maxSize) {
            super(16, 0.75f, true);
            this.maxSize = maxSize;
        }

        /**
         * 判断是否需要淘汰最久未使用的条目。
         *
         * @param eldest 当前最久未使用的条目
         * @return 超出容量上限时返回 {@code true}
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LlmClient> eldest) {
            return size() > maxSize;
        }
    }
}
