package zcd.jellyfish.infra.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ProviderContribution;
import zcd.jellyfish.api.extension.ProviderRegistrationRequest;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.extension.ExtensionRegistry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * LLM 客户端工厂：按 {@link Provider#getType()} 查找 {@link LlmClientCreator} 并创建/复用客户端。
 * <p>
 * 所有实现通过 Dagger2 以 {@code Map<String, LlmClientCreator>} 的形式注册（见 {@code LlmModule}）。
 * 创建出的客户端按 provider 的配置签名缓存：配置不变时复用同一个客户端（底层共享 OkHttpClient 连接池）；
 * apiKey / baseUrl 变化时会创建新客户端。
 * <p>
 * <b>插件可以增加新的 provider 类型</b>：内核自带的类型查不到时，再用该类型去问注册表
 * （{@link ProviderRegistrationRequest}），拿到传输实现就套上 {@link PluginLlmClientAdapter} 返回。
 * 因此路由顺序是硬约束：
 * <ol>
 *     <li><b>内核自带类型优先，插件永远盖不掉它</b>。这不只是先来后到：传输请求里带的是
 *     已解析好的 apiKey，一个能顶替 {@code openai} 的插件等于把所有用户的密钥转发到自己的服务器上。
 *     重复注册只记 WARN 并忽略，插件卸载也不会把内核类型一起带走；</li>
 *     <li>插件类型才问注册表，问一次建一个客户端并按 provider 配置签名缓存，与内置类型同一套缓存；</li>
 *     <li>注册表里没人接该类型时，报错要给出<b>可执行的下一步</b>，而不是只说「找不到客户端」。</li>
 * </ol>
 * 插件停止后自动失效：注册表按 owner 回收注册（fail-closed），后续查找会回到第 3 条。
 * <b>已知边界</b>：不等待在途调用结束——正在跑的那一次会按它自己的取消令牌走完。
 *
 * @author zcd
 */
@Singleton
public class LlmClientFactory {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(LlmClientFactory.class);

    /** 缓存客户端数量上限，超出后按 LRU 淘汰，避免配置反复变更时旧签名客户端无限堆积。 */
    private static final int MAX_CACHED_CLIENTS = 128;

    /** provider type（已规范化为小写）到客户端创建器的映射。 */
    private final Map<String, LlmClientCreator> creators = new ConcurrentHashMap<>();

    /** 插件注册表的查询入口，用于接管内核没有的 provider 类型。 */
    private final ExtensionRegistry extensions;

    /** 流式线程池，转发给插件适配器使用。 */
    private final ExecutorService streamExecutor;

    /** 按 provider 配置签名缓存的客户端实例，线程安全且按 LRU 淘汰。 */
    private final Map<String, LlmClient> clientCache =
            Collections.synchronizedMap(new ClientCache(MAX_CACHED_CLIENTS));

    /**
     * 构造工厂。
     *
     * @param creators       Dagger2 注入的 provider type 到创建器的映射，可为 {@code null}
     * @param extensions     扩展注册表，用于查询插件提供的 provider 类型
     * @param streamExecutor 流式线程池，插件适配器要在它上面跑阻塞式传输
     */
    @Inject
    public LlmClientFactory(Map<String, LlmClientCreator> creators, ExtensionRegistry extensions,
                            ExecutorService streamExecutor) {
        if (creators != null) {
            this.creators.putAll(creators);
        }
        this.extensions = extensions;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 获取内核自带的 provider type 集合的只读快照。
     * <p>
     * <b>它不包含插件提供的类型</b>：调用方需要区分「内核认识」与「插件认识」时用这个，
     * 例如目录发现只应当去问插件接管的那些类型（内核类型有固定的已知模型来源）。
     *
     * @return provider type 到创建器的只读映射
     */
    public Map<String, LlmClientCreator> getRegisteredTypes() {
        return Collections.unmodifiableMap(new HashMap<>(creators));
    }

    /**
     * 获取内核自带的 provider type 集合。
     *
     * @return 已规范化的 type 集合，可能为空但不会为 {@code null}
     */
    public Set<String> builtinTypes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(creators.keySet()));
    }

    /**
     * 判断内核是否自带指定 provider type。
     *
     * @param type provider type，忽略大小写与首尾空白
     * @return 内核已注册对应创建器时返回 {@code true}
     */
    public boolean isBuiltinType(String type) {
        String normalized = normalize(type);
        return normalized != null && creators.containsKey(normalized);
    }

    /**
     * 判断是否有实现能处理指定 provider type（内核或插件）。
     * <p>
     * <b>它只回答「此刻有没有」</b>，不建立任何长期承诺：插件停止后同一个类型会重新变成不支持。
     *
     * @param type provider type，忽略大小写与首尾空白
     * @return 已注册对应创建器、或插件接管了该类型时返回 {@code true}
     */
    public boolean supports(String type) {
        if (isBuiltinType(type)) {
            return true;
        }
        return findPluginTransport(type) != null;
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
        // 内核自带类型先行；只有它不认识该类型时才去问注册表
        ProviderContribution contribution = null;
        if (!creators.containsKey(type)) {
            contribution = findPluginTransport(type);
            if (contribution == null) {
                throw unknownType(type, provider);
            }
        }
        String cacheKey = cacheKey(provider, type);
        // 锁内完成「查缓存 + 创建 + 回填」：既保证 LRU 访问顺序的线程安全，也避免并发重复创建客户端
        synchronized (clientCache) {
            LlmClient cached = clientCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            LlmClient created;
            if (contribution != null) {
                LOG.info("provider 类型由插件接管: type={} plugin={} provider={}",
                        type, contribution.getDisplayName(), provider.getName());
                created = new PluginLlmClientAdapter(provider, contribution, streamExecutor);
            } else {
                created = creators.get(type).create(provider);
                if (created == null) {
                    throw new JellyfishException("failed to create LlmClient for provider: " + provider.getName());
                }
            }
            clientCache.put(cacheKey, created);
            return created;
        }
    }

    /**
     * 构造「未知 provider 类型」的报错，附上可执行的下一步。
     * <p>
     * 只说「找不到客户端」时，用户只能靠翻源码才知道该装什么；把内核认得的类型一并列出来，
     * 他至少能把配置改成能跑的那个。
     *
     * @param type     已规范化的 provider 类型
     * @param provider 出错的 provider
     * @return 报错
     */
    private JellyfishException unknownType(String type, Provider provider) {
        return new JellyfishException("unsupported provider type: " + type
                + " (provider=" + provider.getName() + ")"
                + "; builtin types: " + creators.keySet()
                + ". 装一个提供该类型的插件，或把它改成上面已支持的类型");
    }

    /**
     * 问注册表「这个 provider 类型归不归你管」。
     * <p>
     * <b>同键唯一由注册表在注册期保证</b>：第二个用同一类型注册的插件会在<i>注册那一刻</i>拿到
     * 重复错误（要顶替必须显式声明覆盖），因此这里不可能遇到多命中。
     * <p>
     * <b>处理器抛错一律按「没人接」处理</b>：一个插件挂掉不该让整条路由失败。只记 WARN——
     * 用户真正需要知道的是「这个类型没人实现」。
     *
     * @param type provider 类型
     * @return 插件的贡献，没人接管或处理失败时返回 {@code null}
     */
    private ProviderContribution findPluginTransport(String type) {
        String normalized = normalize(type);
        if (normalized == null || extensions == null) {
            return null;
        }
        List<ExtensionHandler<ProviderRegistrationRequest, ProviderContribution>> handlers;
        try {
            handlers = extensions.handlers(ProviderRegistrationRequest.class, normalized);
        } catch (RuntimeException e) {
            LOG.warn("查询插件 provider 类型失败: type={}", normalized, e);
            return null;
        }
        if (handlers.isEmpty()) {
            return null;
        }
        try {
            ProviderContribution contribution = extensions.invoke(handlers.get(0),
                    new ProviderRegistrationRequest(normalized));
            // 「我在但这个类型不是我的」与「我不在」在这里合并：两者对路由的含义完全一致
            return contribution != null && contribution.isSupported() && contribution.getTransport() != null
                    ? contribution : null;
        } catch (RuntimeException e) {
            LOG.warn("插件 provider 类型查询失败: type={}", normalized, e);
            return null;
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
     * <p>
     * <b>签名不含 {@code sampling} / {@code extraBody} / {@code extraHeaders}</b>：前两项随请求下发
     * （每次组装时从配置现取），改了就生效；{@code extraHeaders} 由客户端在构造时扣下，
     * 因此它依赖 {@code /reload} 路径上的 {@code clearCache()}——配置热更新一定会清缓存，
     * 手工构造的调用点若要改头必须自己清。
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
