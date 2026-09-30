package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code models.json} 里单个 provider 的 {@code cache} 段：让<b>缓存更可能是热的</b>的旋钮。
 * <p>
 * <b>为什么这些旋钮挂在 provider 上而不是全局</b>：两条都与<b>具体厂商</b>的缓存实现绑死——
 * 缓存 TTL 是 5 分钟还是几小时、是否支持显式的缓存路由键，各家都不一样。挂在全局就等于逼着
 * 用户在明明不会生效的 provider 上也能配上它们，然后困惑为什么没区别。
 * <p>
 * <b>两项都是可选的、缺省关的</b>，因为它们都得先满足「厂商确实这么干」才谈得上收益：
 * <ul>
 *     <li>{@code promptCacheKey}：把会话标识作为缓存路由键下发给厂商，让同一会话的请求尽量落到
 *     同一台机器上。目前明确支持的是 OpenAI 系（{@code prompt_cache_key} 字段）；缺省关闭，
 *     因为老模型/老端点收到不认识的字段可能直接报错。</li>
 *     <li>{@code keepAliveSeconds}：空闲时每隔这么久重发一次「复用同一前缀」的最小请求，把缓存
 *     的 TTL 续上。<b>它是要花钱的</b>（每次命中按约 0.1× 计费），因此缺省是 {@code 0}（关闭）；
 *     而且<b>每个空闲期最多只续 {@value #MAX_KEEP_ALIVE_ROUNDS} 次</b>，见 {@link #getKeepAliveSeconds()}。</li>
 * </ul>
 * 非法值（负数、超出上限）回退到缺省值：配置问题不阻断启动是本仓库的既有口径。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class ProviderCacheSettings {

    /** 是否下发缓存路由键的缺省值：关闭。 */
    public static final boolean DEFAULT_PROMPT_CACHE_KEY = false;

    /** 保活间隔的缺省值：{@code 0} 表示关闭。 */
    public static final int DEFAULT_KEEP_ALIVE_SECONDS = 0;

    /** 保活间隔的上限（1 小时）：再长就没有续 TTL 的意义了。 */
    public static final int MAX_KEEP_ALIVE_SECONDS = 3600;

    /**
     * 每个空闲期最多续几次。
     * <p>
     * <b>这个上限是「这笔钱值不值」的闸门</b>，不是随手取的：一次保活命中按约 0.1× 计费，
     * 而它要防的是一次未命中（约 1×，即多花 0.9×）。因此续 {@value} 次的总代价约 {@code 0.3×}
     * 前缀，只要它能换掉一次未命中就是净赚；再往上续则开始有亏的风险，而那时用户多半已经离开。
     */
    public static final int MAX_KEEP_ALIVE_ROUNDS = 3;

    /** 是否把会话标识作为缓存路由键下发。 */
    private final boolean promptCacheKey;

    /** 空闲时重发保活请求的间隔秒数，{@code 0} 表示关闭。 */
    private final int keepAliveSeconds;

    /**
     * 构造缺省缓存设置。
     */
    public ProviderCacheSettings() {
        this(null, null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param promptCacheKey    是否下发缓存路由键，缺省按 {@link #DEFAULT_PROMPT_CACHE_KEY}
     * @param keepAliveSeconds  保活间隔秒数，负数、超过 {@link #MAX_KEEP_ALIVE_SECONDS} 或缺省按
     *                          {@link #DEFAULT_KEEP_ALIVE_SECONDS}
     */
    @JsonCreator
    public ProviderCacheSettings(@JsonProperty("promptCacheKey") Boolean promptCacheKey,
                                @JsonProperty("keepAliveSeconds") Integer keepAliveSeconds) {
        this.promptCacheKey = promptCacheKey == null ? DEFAULT_PROMPT_CACHE_KEY : promptCacheKey;
        this.keepAliveSeconds = keepAliveSeconds != null && keepAliveSeconds >= 0
                && keepAliveSeconds <= MAX_KEEP_ALIVE_SECONDS
                ? keepAliveSeconds : DEFAULT_KEEP_ALIVE_SECONDS;
    }

    /**
     * 判断是否下发缓存路由键。
     *
     * @return 下发返回 {@code true}
     */
    public boolean isPromptCacheKey() {
        return promptCacheKey;
    }

    /**
     * 获取保活间隔秒数。
     * <p>
     * {@code 0} 表示关闭。开启时，每个空闲期最多续 {@link #MAX_KEEP_ALIVE_ROUNDS} 次
     * （理由见那个常量），之后不再花钱。
     *
     * @return 秒数，非负
     */
    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    /**
     * 判断是否与缺省值完全一致。
     *
     * @return 两项都等于缺省值返回 {@code true}
     */
    public boolean isDefault() {
        return promptCacheKey == DEFAULT_PROMPT_CACHE_KEY
                && keepAliveSeconds == DEFAULT_KEEP_ALIVE_SECONDS;
    }
}
