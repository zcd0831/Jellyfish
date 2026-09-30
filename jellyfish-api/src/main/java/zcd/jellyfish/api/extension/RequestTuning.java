package zcd.jellyfish.api.extension;

import java.util.Objects;

/**
 * 出站请求的缓存调优：插件对「这一次请求该怎么跟厂商谈缓存」的回答。
 * <p>
 * <b>白名单是类型本身，不是一句约定</b>：本类只有三个缓存相关的字段，<b>没有</b>
 * {@code systemPrompt}、{@code messages}、{@code tools}、{@code model} 这类字段——插件因此
 * 在编译期就无法改写请求内容。这是刻意的：前缀不变量是<b>全局</b>性质，取决于所有组装决策的合取，
 * 任何一处被改坏整条前缀就作废。把「改内容」的能力交给插件，等于让任何一个第三方插件的每次改动
 * 都能把命中率打到 0。
 * <p>
 * <b>三个字段都不表态时，行为与没有这个扩展点完全一致</b>：缺省值仍由内核与 provider 配置决定。
 *
 * @author zcd
 */
public final class RequestTuning {

    /**
     * 内核缺省的缓存断点数：<b>稳定前端 + 会话尾部</b>两个。
     * <p>
     * 放在 api 侧而不是某个客户端里，是因为它属于<b>插件与内核之间的契约</b>：插件要知道
     * 「不表态等于什么」，才能判断自己该不该表态。
     */
    public static final int DEFAULT_CACHE_BREAKPOINTS = 2;

    /** 缓存断点数的上限：目前只有「稳定前端」与「会话尾部」两个可放的位置。 */
    public static final int MAX_CACHE_BREAKPOINTS = 2;

    /**
     * 缓存路由键；{@code null} 表示不表态。
     * <p>
     * 各厂商对它的叫法不同——OpenAI 系为 {@code prompt_cache_key}。它的作用是让同一会话的请求
     * 尽量落到持有相同前缀的机器上，<b>不参与内容，因此不影响回答</b>。
     * <p>
     * <b>同一会话必须一直用同一个值</b>，否则请求会被散到不同机器上各建一份缓存，命中率反而更差。
     * 内核缺省用会话标识，通常不需要插件介入。
     */
    private final String cacheKey;

    /**
     * 缓存保留策略；{@code null} 表示不表态（内核不下发该字段）。
     * <p>
     * <b>取值由厂商约定，且不是一套</b>：Anthropic 走 {@code cache_control.ttl}（{@code "5m"} /
     * {@code "1h"}），OpenAI 系走 {@code prompt_cache_retention}（{@code "in_memory"} / {@code "24h"}）。
     * 内核原样下发，不替插件猜——正因为各厂商取值不同、且会随模型换代改变，内核不适合作出缺省值。
     * 插件可以据 {@link RequestTuningRequest#getProviderType()} 分支。
     * <p>
     * <b>下发前请确认目标厂商认这个字段</b>：不认的端点可能直接报 400。内核缺省不下发它，
     * 因此不设置就没有任何风险。
     */
    private final String cacheRetention;

    /**
     * 缓存断点数；{@code null} 表示不表态（用 {@link #DEFAULT_CACHE_BREAKPOINTS}）。
     * <p>
     * <b>为什么是「数量」而不是「位置」</b>：位置决定了前缀从哪里开始可复用，那是必须由内核独占的
     * 算法；数量则只是「用几个」。目前只有两个位置可放，按<b>由前到后</b>的顺序取前 N 个：
     * <ul>
     *     <li>{@code 0}——<b>关闭该厂商的缓存</b>。适合「前缀本来每次都变、加了标记也命中不了」的会话；</li>
     *     <li>{@code 1}——只打<b>稳定前端</b>（system prompt / 工具定义）。后面无论怎么变，
     *     这一段的缓存都还在；</li>
     *     <li>{@code 2}——稳定前端 + <b>会话尾部</b>（默认）。</li>
     * </ul>
     * 超出 {@code [0, }{@link #MAX_CACHE_BREAKPOINTS}{@code ]} 时内核钳制到该区间。
     * <p>
     * 只对<b>需要显式标注断点</b>的厂商有效（Anthropic）；DeepSeek 与 OpenAI 系默认就按前缀自动缓存，
     * 它们会忽略本字段。
     */
    private final Integer cacheBreakpoints;

    /**
     * 构造缓存调优。
     *
     * @param cacheKey        缓存路由键，可为 {@code null}
     * @param cacheRetention  缓存保留策略，可为 {@code null}
     * @param cacheBreakpoints 缓存断点数，可为 {@code null}
     */
    public RequestTuning(String cacheKey, String cacheRetention, Integer cacheBreakpoints) {
        this.cacheKey = cacheKey;
        this.cacheRetention = cacheRetention;
        this.cacheBreakpoints = cacheBreakpoints;
    }

    /**
     * 构造一个「什么都不表态」的调优，行为与没有本扩展点时一致。
     *
     * @return 空调优
     */
    public static RequestTuning empty() {
        return new RequestTuning(null, null, null);
    }

    /**
     * 获取缓存路由键。
     *
     * @return 缓存路由键，可能为 {@code null}
     */
    public String getCacheKey() {
        return cacheKey;
    }

    /**
     * 获取缓存保留策略。
     *
     * @return 保留策略，可能为 {@code null}
     */
    public String getCacheRetention() {
        return cacheRetention;
    }

    /**
     * 获取缓存断点数。
     *
     * @return 断点数，可能为 {@code null}（不表态）
     */
    public Integer getCacheBreakpoints() {
        return cacheBreakpoints;
    }

    /**
     * 判断三个字段是否都没表态。
     *
     * @return 都没表态返回 {@code true}
     */
    public boolean isEmpty() {
        return cacheKey == null && cacheRetention == null && cacheBreakpoints == null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RequestTuning)) {
            return false;
        }
        RequestTuning that = (RequestTuning) other;
        return Objects.equals(cacheKey, that.cacheKey)
                && Objects.equals(cacheRetention, that.cacheRetention)
                && Objects.equals(cacheBreakpoints, that.cacheBreakpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cacheKey, cacheRetention, cacheBreakpoints);
    }

    @Override
    public String toString() {
        return "RequestTuning{cacheKey=" + cacheKey + ", cacheRetention=" + cacheRetention
                + ", cacheBreakpoints=" + cacheBreakpoints + '}';
    }
}
