package zcd.jellyfish.api.llm;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次调用的传输层请求：厂商无关的字段 + 目标端点信息 + 取消令牌。
 * <p>
 * <b>为什么是「厂商无关的字段」而不是厂商格式的请求体</b>：插件接管的是「把这份归一化的意图翻成
 * 我家协议并发出去」，不是「内核的序列化逻辑改由插件写」。因此这里只出现各家都有对应物的字段
 * （模型、系统提示词、消息、工具、采样参数）。
 * <p>
 * <b>但厂商私有字段有一个受控的入口</b>：{@link #getVendorBody()} 与 {@link #getVendorHeaders()}——
 * 用户在 {@code models.json} 里配的、内核<b>不解释其含义</b>的键值。它们是「原样搬运」而不是
 * 「内核也开始认识厂商协议」：内核不按厂商语义分支、不校验字段名，只保证内核自己生成的那些键
 * （{@code model} / {@code messages} / {@code tools} …）不会被人从这条路覆盖掉。
 * <p>
 * <b>{@code apiKey} 是内核已经解析好的最终值</b>（配置里的 {@code ${ENV_VAR}} 已插值、双源合并已完成），
 * 插件<b>不需要也不允许</b>自己去读配置文件——那会让「用户只需要维护一处凭据」失效。相应地，
 * <b>本类型与它携带的凭据绝不能进日志</b>：{@link #toString()} 已经把它脱敏成占位符，
 * 插件自己打日志时也必须照此处理。
 * <p>
 * <b>{@code baseUrl} 为 {@code null} 表示「按你家的默认地址」</b>：内置客户端都是这个语义，
 * 插件应当给出与自家协议匹配的默认值，而不是当成一次配置错误。
 * <p>
 * <b>缓存三件套是「厂商约定」而不是内核知识</b>：{@code cacheKey} 是路由键（OpenAI 系为
 * {@code prompt_cache_key}），{@code cacheRetention} 是保留策略（Anthropic 为
 * {@code cache_control.ttl}），{@code cacheBreakpoints} 是要打几个断点。内核<b>不替厂商猜缺省值</b>：
 * {@code null} 就是「不下发该字段」，插件照办即可。
 * <p>
 * <b>{@code minimalOutput} 是一个意图而不是一个数字</b>：缓存保活只要求「碰一下、把 TTL 续上」，
 * 各家对「最省形式」的合法写法不同（Anthropic 接受 {@code max_tokens: 0}，OpenAI 系的下限是 1），
 * 因此换算是插件的事。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class LlmTransportRequest {

    /** provider 名（{@code models.json} 里的 key），供插件区分同一类型下的多个端点与做日志归因。 */
    private final String providerName;

    /** provider 类型，即插件注册时用的那个键。 */
    private final String providerType;

    /** 已解析好的访问密钥。 */
    private final String apiKey;

    /** 服务地址，{@code null} 表示按插件自己的默认地址。 */
    private final String baseUrl;

    /** 模型标识，即厂商接口里的 {@code model} 字段。 */
    private final String model;

    /** 系统提示词。 */
    private final String systemPrompt;

    /** 对话消息，不含系统提示词。 */
    private final List<LlmTransportMessage> messages;

    /** 可调用的工具定义。 */
    private final List<LlmTransportTool> tools;

    /** 工具选择策略，取值由各厂商约定。 */
    private final String toolChoice;

    /** 采样温度，{@code null} 表示未设置。 */
    private final Double temperature;

    /** 最大生成 token 数，{@code null} 表示未设置。 */
    private final Integer maxTokens;

    /**
     * 承载 {@link #maxTokens} 的请求体字段名。
     * <p>
     * <b>{@code null} 的含义是「用户没配」，不是「你自己挑」</b>：内核的缺省是 {@code max_tokens}，
     * 端点若只认 {@code max_completion_tokens}（OpenAI 的推理模型与 gpt-5 之后），用户会在
     * {@code models.json} 里把它显式配出来，那时这个值非空。因此插件照下面两种做法都对，
     * 但别把 {@code null} 理解成「省略该字段」——那会让输出上限凭空消失：
     * <ul>
     *     <li>直接用非空值作为键名，{@code null} 时用 {@code max_tokens}；</li>
     *     <li>或者忽略它、按自家协议固定一个名字（那就与内核的配置项脱钩了，用户配了不生效）。</li>
     * </ul>
     */
    private final String maxTokensField;

    /** 核采样概率，{@code null} 表示未设置。 */
    private final Double topP;

    /** Top-K 采样，{@code null} 表示未设置。 */
    private final Integer topK;

    /** 随机种子，{@code null} 表示未设置。 */
    private final Long seed;

    /** 频率惩罚，{@code null} 表示未设置。 */
    private final Double frequencyPenalty;

    /** 存在惩罚，{@code null} 表示未设置。 */
    private final Double presencePenalty;

    /** 停止序列。 */
    private final List<String> stop;

    /** 缓存路由键，{@code null} 表示不下发。 */
    private final String cacheKey;

    /** 缓存保留策略，{@code null} 表示不下发。 */
    private final String cacheRetention;

    /** 缓存断点数，{@code null} 表示未设置。 */
    private final Integer cacheBreakpoints;

    /** 是否只要求最省输出。 */
    private final boolean minimalOutput;

    /**
     * 直通请求体字段：用户在 {@code models.json} 里写的厂商私有参数，内核<b>不解释其含义</b>。
     * <p>
     * <b>为什么把它交给插件</b>：接管某个 provider 类型的插件若看不到它，用户会遇到
     * 「换了 type 之后配的 reasoning_effort 就不再生效」——同一个配置段，行为却取决于谁实现了传输，
     * 那不是可解释的约定。插件可以选择忽略（它自己的协议可能根本没有这些字段），
     * 但忽略必须是插件的决定，而不是内核没告诉它。
     * <p>
     * <b>键已清洗</b>：内核自己生成的那些键（{@code model} / {@code messages} / {@code tools} …）
     * 在解析配置时就被挡掉了，{@code temperature} 这类已有正式入口的也一样。
     * <b>插件不得再往里塞内核的字段</b>：那是内核的保留键，插件的立场由 {@code LlmRequest} 的正式字段表达。
     */
    private final Map<String, Object> vendorBody;

    /**
     * 直通请求头：用户在 {@code models.json} 的 provider 段里写的自定义头部。
     * <p>
     * 与 {@link #vendorBody} 同一立场：内核不解释，插件照发即可。协议头
     * （{@code Content-Type} / {@code Accept} / {@code Host} / {@code Content-Length}）已在内核侧挡掉，
     * 因为它们由传输实现自己决定，用户写死会造成请求与连接不自洽。
     * <p>
     * <b>值可能与密钥同级敏感</b>（自定义鉴权头就是密钥），与 {@link #getApiKey()} 同一口径：
     * 不得写进日志、事件载荷或错误信息。
     */
    private final Map<String, String> vendorHeaders;

    /** 取消令牌。 */
    private final CancellationToken cancellationToken;

    /**
     * 由构建器构造请求，并对集合做防御性拷贝。
     *
     * @param builder 请求构建器
     */
    private LlmTransportRequest(Builder builder) {
        this.providerName = builder.providerName;
        this.providerType = builder.providerType;
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.messages = Collections.unmodifiableList(
                new ArrayList<LlmTransportMessage>(builder.messages));
        this.tools = builder.tools == null
                ? Collections.<LlmTransportTool>emptyList()
                : Collections.unmodifiableList(new ArrayList<LlmTransportTool>(builder.tools));
        this.toolChoice = builder.toolChoice;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.maxTokensField = builder.maxTokensField;
        this.topP = builder.topP;
        this.topK = builder.topK;
        this.seed = builder.seed;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.stop = builder.stop == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(builder.stop));
        this.cacheKey = builder.cacheKey;
        this.cacheRetention = builder.cacheRetention;
        this.cacheBreakpoints = builder.cacheBreakpoints;
        this.minimalOutput = builder.minimalOutput;
        this.vendorBody = builder.vendorBody == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.vendorBody));
        this.vendorHeaders = builder.vendorHeaders == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.vendorHeaders));
        this.cancellationToken = builder.cancellationToken == null
                ? CancellationToken.NONE : builder.cancellationToken;
    }

    /**
     * 创建请求构建器。
     *
     * @param providerType provider 类型，不可为空白
     * @param model        模型标识，不可为空白
     * @return 请求构建器
     */
    public static Builder builder(String providerType, String model) {
        return new Builder(providerType, model);
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderType() {
        return providerType;
    }

    /**
     * 获取已解析好的访问密钥。
     * <p>
     * <b>这是凭据</b>：只能用于构造发往 {@link #getBaseUrl()} 的请求，不得写进日志、事件载荷或
     * 错误信息。{@link #toString()} 已做脱敏。
     *
     * @return 访问密钥，可能为 {@code null}（该类型不需要密钥时）
     */
    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<LlmTransportMessage> getMessages() {
        return messages;
    }

    public List<LlmTransportTool> getTools() {
        return tools;
    }

    public String getToolChoice() {
        return toolChoice;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    /**
     * 获取承载最大生成 token 数的请求体字段名。
     * <p>
     * <b>{@code null} 表示用户没配</b>，内核的缺省是 {@code max_tokens}；非空时请原样用作请求体里的键名
     * （端点只认它时才有人会配出来）。<b>不要把它当成「省略该字段」的信号</b>——那等于把输出上限丢掉。
     *
     * @return 字段名，用户没配时为 {@code null}
     */
    public String getMaxTokensField() {
        return maxTokensField;
    }

    public Double getTopP() {
        return topP;
    }

    /**
     * 获取 Top-K 采样值。
     * <p>
     * <b>插件要自己判断自家协议认不认</b>：这一项只有 Anthropic 与 Gemini 有对应字段，
     * 内核只声明意图，不做「谁认」的判断。
     *
     * @return Top-K 值，未设置时为 {@code null}
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * 获取随机种子。
     *
     * @return 随机种子，未设置时为 {@code null}
     */
    public Long getSeed() {
        return seed;
    }

    /**
     * 获取频率惩罚。
     *
     * @return 频率惩罚，未设置时为 {@code null}
     */
    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    /**
     * 获取存在惩罚。
     *
     * @return 存在惩罚，未设置时为 {@code null}
     */
    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public List<String> getStop() {
        return stop;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public String getCacheRetention() {
        return cacheRetention;
    }

    public Integer getCacheBreakpoints() {
        return cacheBreakpoints;
    }

    public boolean isMinimalOutput() {
        return minimalOutput;
    }

    /**
     * 获取取消令牌。
     * <p>
     * 阻塞前用 {@link CancellationToken#onCancel(Runnable)} 登记「中断自家 HTTP 调用」这个动作，
     * 而不是轮询——轮询只能回答「此刻是否已取消」，救不了一个正在读流的长调用。
     *
     * @return 取消令牌，保证非 {@code null}
     */
    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    /**
     * 获取直通请求体字段。
     *
     * @return 只读映射，可能为空但不会为 {@code null}
     */
    public Map<String, Object> getVendorBody() {
        return vendorBody;
    }

    /**
     * 获取直通请求头。
     * <p>
     * <b>这是可能含凭据的数据</b>（自定义鉴权头就是密钥），与 {@link #getApiKey()} 同一口径：
     * 只能用于构造发往 {@link #getBaseUrl()} 的请求。
     *
     * @return 只读映射，可能为空但不会为 {@code null}
     */
    public Map<String, String> getVendorHeaders() {
        return vendorHeaders;
    }

    /**
     * 判断本次调用了声明工具。
     *
     * @return 声明了工具时返回 {@code true}
     */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * 返回本次请求的可读表示。{@code apiKey} 只以「有没有设」的形式出现，不出现内容。
     *
     * @return 描述字符串
     */
    @Override
    public String toString() {
        return "LlmTransportRequest{provider=" + providerName + '/' + providerType
                + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "none" : "***")
                + ", baseUrl=" + baseUrl
                + ", model=" + model
                + ", messages=" + messages.size()
                + ", tools=" + tools.size()
                + ", minimalOutput=" + minimalOutput + '}';
    }

    /**
     * 请求构建器。除 provider 类型与模型外均可选，链式调用后通过 {@link #build()} 生成不可变请求。
     * <p>
     * 字段多且绝大多数调用点只设置少数几个，因此用构建器而不是一长串位置参数——
     * 位置参数在这里极易把 {@code temperature} 与 {@code topP} 这类同类型字段写反。
     *
     * @author zcd
     */
    public static final class Builder {

        /** provider 类型，构造时确定且不可变。 */
        private final String providerType;

        /** 模型标识，构造时确定且不可变。 */
        private final String model;

        /** provider 名。 */
        private String providerName;

        /** 访问密钥。 */
        private String apiKey;

        /** 服务地址。 */
        private String baseUrl;

        /** 系统提示词。 */
        private String systemPrompt;

        /** 对话消息。 */
        private final List<LlmTransportMessage> messages = new ArrayList<LlmTransportMessage>();

        /** 工具定义。 */
        private List<LlmTransportTool> tools;

        /** 工具选择策略。 */
        private String toolChoice;

        /** 采样温度。 */
        private Double temperature;

        /** 最大生成 token 数。 */
        private Integer maxTokens;

        /** 承载最大生成 token 数的请求体字段名。 */
        private String maxTokensField;

        /** 核采样概率。 */
        private Double topP;

        /** Top-K 采样。 */
        private Integer topK;

        /** 随机种子。 */
        private Long seed;

        /** 频率惩罚。 */
        private Double frequencyPenalty;

        /** 存在惩罚。 */
        private Double presencePenalty;

        /** 停止序列。 */
        private List<String> stop;

        /** 缓存路由键。 */
        private String cacheKey;

        /** 缓存保留策略。 */
        private String cacheRetention;

        /** 缓存断点数。 */
        private Integer cacheBreakpoints;

        /** 是否只要求最省输出。 */
        private boolean minimalOutput;

        /** 直通请求体字段。 */
        private Map<String, Object> vendorBody;

        /** 直通请求头。 */
        private Map<String, String> vendorHeaders;

        /** 取消令牌。 */
        private CancellationToken cancellationToken;

        /**
         * 构造构建器。
         *
         * @param providerType provider 类型
         * @param model        模型标识
         */
        private Builder(String providerType, String model) {
            this.providerType = providerType;
            this.model = model;
        }

        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 追加一条消息，{@code null} 会被忽略。
         *
         * @param message 对话消息
         * @return 当前构建器
         */
        public Builder message(LlmTransportMessage message) {
            if (message != null) {
                this.messages.add(message);
            }
            return this;
        }

        /**
         * 批量追加消息，{@code null} 会被忽略。
         *
         * @param messages 对话消息列表
         * @return 当前构建器
         */
        public Builder messages(List<LlmTransportMessage> messages) {
            if (messages != null) {
                this.messages.addAll(messages);
            }
            return this;
        }

        public Builder tools(List<LlmTransportTool> tools) {
            this.tools = tools;
            return this;
        }

        public Builder toolChoice(String toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * 设置承载最大生成 token 数的请求体字段名。空串归一成 {@code null}（同 {@link #cacheKey(String)}）。
         *
         * @param maxTokensField 字段名，可为 {@code null}
         * @return 当前构建器
         */
        public Builder maxTokensField(String maxTokensField) {
            this.maxTokensField = maxTokensField == null || maxTokensField.trim().isEmpty()
                    ? null : maxTokensField.trim();
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * 设置 Top-K 采样。
         *
         * @param topK Top-K 值
         * @return 当前构建器
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * 设置随机种子。
         *
         * @param seed 随机种子
         * @return 当前构建器
         */
        public Builder seed(Long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * 设置频率惩罚。
         *
         * @param frequencyPenalty 频率惩罚
         * @return 当前构建器
         */
        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * 设置存在惩罚。
         *
         * @param presencePenalty 存在惩罚
         * @return 当前构建器
         */
        public Builder presencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        /**
         * 设置缓存路由键。
         * <p>
         * <b>空串归一成 {@code null}</b>：调用方常常直接拿一个可能为空的标识来设，而「设了一个空值」
         * 与「没设」在厂商侧是两回事（前者会下发一个空字段）。归一之后只留一种含义。
         *
         * @param cacheKey 缓存路由键，可为 {@code null}
         * @return 当前构建器
         */
        public Builder cacheKey(String cacheKey) {
            this.cacheKey = cacheKey == null || cacheKey.trim().isEmpty() ? null : cacheKey.trim();
            return this;
        }

        /**
         * 设置缓存保留策略，空串归一成 {@code null}（同 {@link #cacheKey(String)}）。
         *
         * @param cacheRetention 保留策略，可为 {@code null}
         * @return 当前构建器
         */
        public Builder cacheRetention(String cacheRetention) {
            this.cacheRetention = cacheRetention == null || cacheRetention.trim().isEmpty()
                    ? null : cacheRetention.trim();
            return this;
        }

        public Builder cacheBreakpoints(Integer cacheBreakpoints) {
            this.cacheBreakpoints = cacheBreakpoints;
            return this;
        }

        /**
         * 声明本次调用不要求生成内容，只要厂商允许的最省形式。
         *
         * @return 当前构建器
         */
        public Builder minimalOutput() {
            this.minimalOutput = true;
            return this;
        }

        /**
         * 设置直通请求体字段。
         *
         * @param vendorBody 直通字段，可为 {@code null}（等价空）
         * @return 当前构建器
         */
        public Builder vendorBody(Map<String, Object> vendorBody) {
            this.vendorBody = vendorBody;
            return this;
        }

        /**
         * 设置直通请求头。
         *
         * @param vendorHeaders 直通头部，可为 {@code null}（等价空）
         * @return 当前构建器
         */
        public Builder vendorHeaders(Map<String, String> vendorHeaders) {
            this.vendorHeaders = vendorHeaders;
            return this;
        }

        public Builder cancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = cancellationToken;
            return this;
        }

        /**
         * 构建不可变请求。
         *
         * @return 传输层请求
         * @throws JellyfishException provider 类型或模型为空白时抛出
         */
        public LlmTransportRequest build() {
            if (providerType == null || providerType.trim().isEmpty()) {
                throw new JellyfishException("transport request providerType must not be blank");
            }
            if (model == null || model.trim().isEmpty()) {
                throw new JellyfishException("transport request model must not be blank");
            }
            return new LlmTransportRequest(this);
        }
    }
}
