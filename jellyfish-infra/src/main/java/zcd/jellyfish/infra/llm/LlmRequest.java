package zcd.jellyfish.infra.llm;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 与各厂商接口无关的统一请求模型。不可变，通过 {@link #builder(String)} 构建。
 *
 * @author zcd
 */
public final class LlmRequest {

    /** 模型名，不同 provider 下的取值不同。 */
    private final String model;

    /** 系统提示词，会作为独立消息或独立字段下发给厂商。 */
    private final String systemPrompt;

    /** 对话消息列表，不包含系统提示词。 */
    private final List<LlmMessage> messages;

    /** 采样温度。 */
    private final Double temperature;

    /** 最大生成 token 数。 */
    private final Integer maxTokens;

    /** 核采样概率。 */
    private final Double topP;

    /** 停止序列。 */
    private final List<String> stop;

    /** 可调用的工具定义。 */
    private final List<LlmTool> tools;

    /** 工具选择策略，取值由各厂商约定。 */
    private final String toolChoice;

    /**
     * 缓存路由键，取值由各厂商约定（OpenAI 系为 {@code prompt_cache_key}）。
     * <p>
     * <b>它不参与内容，因此不影响正确性，只影响命中率</b>：厂商用它把请求路由到持有相同前缀的机器上。
     * 同一会话应当一直用同一个值（内核用会话标识），这样它的请求才不会被散到不同机器上各建一份缓存。
     */
    private final String cacheKey;

    /**
     * 缓存保留策略；取值由各厂商约定（Anthropic 为 {@code cache_control.ttl}，OpenAI 系为
     * {@code prompt_cache_retention}）。
     * <p>
     * <b>内核不替调用方猜缺省值</b>：同一概念在不同厂商、乃至同一厂商的不同模型上取值都不一样
     * （且会随换代改变），猜错就是一次 400。因此未设置时<b>不下发该字段</b>。
     */
    private final String cacheRetention;

    /**
     * 缓存断点数：需要显式标注断点的厂商（Anthropic）用它决定要不要打断点、打几个。
     * <p>
     * {@code null} 表示未设置，由客户端按 {@code DEFAULT} 语义处理；{@code 0} 表示关闭该厂商的缓存。
     * 按前缀自动缓存的厂商（DeepSeek、OpenAI 系）忽略本字段。
     */
    private final Integer cacheBreakpoints;

    /**
     * 本次调用不要求生成内容，只要厂商允许的最省形式。
     * <p>
     * <b>为什么是一个意图而不是一个数字</b>：最省形式是各家的协议细节，而且各家不同——
     * Anthropic 接受 {@code max_tokens: 0}（明确支持，语义是「只做 prefill 并写缓存、不生成输出」），
     * 而 OpenAI 与 DeepSeek 的下限是 1（{@code 0} 会被 400 拒绝）。因此调用方只说「我不需要输出」，
     * 由各个客户端自己换算成本家合法的写法。把数字写在调用方，等于让内核去记住每一家的下限。
     * <p>
     * 目前只有缓存保活用它：一次保活请求要的是「碰一下缓存、把 TTL 续上」，
     * 输出内容没有意义，而多生成一个 token 就多花一笔钱。
     */
    private final boolean minimalOutput;

    /**
     * 由 builder 构造请求，并对所有集合做防御性拷贝。
     *
     * @param builder 请求构建器
     */
    private LlmRequest(Builder builder) {
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.messages = Collections.unmodifiableList(new ArrayList<>(builder.messages));
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.topP = builder.topP;
        this.stop = builder.stop == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.stop));
        this.tools = builder.tools == null
                ? Collections.<LlmTool>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.toolChoice = builder.toolChoice;
        this.cacheKey = builder.cacheKey;
        this.cacheRetention = builder.cacheRetention;
        this.cacheBreakpoints = builder.cacheBreakpoints;
        this.minimalOutput = builder.minimalOutput;
    }

    /**
     * 创建请求构建器。
     *
     * @param model 模型名，不可为空
     * @return 请求构建器
     */
    public static Builder builder(String model) {
        return new Builder(model);
    }

    /**
     * 获取模型名。
     *
     * @return 模型名
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取系统提示词。
     *
     * @return 系统提示词，未设置时为 {@code null}
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 获取对话消息列表。
     *
     * @return 对话消息，可能为空但不会为 {@code null}
     */
    public List<LlmMessage> getMessages() {
        return messages;
    }

    /**
     * 获取采样温度。
     *
     * @return 采样温度，未设置时为 {@code null}
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * 获取最大生成 token 数。
     *
     * @return 最大生成 token 数，未设置时为 {@code null}
     */
    public Integer getMaxTokens() {
        return maxTokens;
    }

    /**
     * 获取核采样概率。
     *
     * @return 核采样概率，未设置时为 {@code null}
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * 获取停止序列。
     *
     * @return 停止序列，可能为空但不会为 {@code null}
     */
    public List<String> getStop() {
        return stop;
    }

    /**
     * 获取工具定义列表。
     *
     * @return 工具定义，可能为空但不会为 {@code null}
     */
    public List<LlmTool> getTools() {
        return tools;
    }

    /**
     * 获取工具选择策略。
     *
     * @return 工具选择策略，未设置时为 {@code null}
     */
    public String getToolChoice() {
        return toolChoice;
    }

    /**
     * 获取缓存路由键。
     *
     * @return 缓存路由键，未设置时为 {@code null}
     */
    public String getCacheKey() {
        return cacheKey;
    }

    /**
     * 获取缓存保留策略。
     *
     * @return 保留策略，未设置时为 {@code null}
     */
    public String getCacheRetention() {
        return cacheRetention;
    }

    /**
     * 获取缓存断点数。
     *
     * @return 断点数，未设置时为 {@code null}
     */
    public Integer getCacheBreakpoints() {
        return cacheBreakpoints;
    }

    /**
     * 判断本次调用是否只要求厂商允许的最省输出。
     *
     * @return 不要求生成内容时返回 {@code true}
     */
    public boolean isMinimalOutput() {
        return minimalOutput;
    }

    /**
     * 判断本次请求是否声明了工具。
     *
     * @return 声明了工具时返回 {@code true}
     */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * 请求构建器。除 {@code model} 外均可选，链式调用后通过 {@link #build()} 生成不可变请求。
     *
     * @author zcd
     */
    public static final class Builder {

        /** 模型名，构造时确定且不可变。 */
        private final String model;

        /** 系统提示词。 */
        private String systemPrompt;

        /** 对话消息列表，可变，构建时被拷贝。 */
        private final List<LlmMessage> messages = new ArrayList<>();

        /** 采样温度。 */
        private Double temperature;

        /** 最大生成 token 数。 */
        private Integer maxTokens;

        /** 核采样概率。 */
        private Double topP;

        /** 停止序列。 */
        private List<String> stop;

        /** 工具定义。 */
        private List<LlmTool> tools;

        /** 工具选择策略。 */
        private String toolChoice;

        /** 缓存路由键。 */
        private String cacheKey;

        /** 缓存保留策略。 */
        private String cacheRetention;

        /** 缓存断点数。 */
        private Integer cacheBreakpoints;

        /** 是否只要求厂商允许的最省输出。 */
        private boolean minimalOutput;

        /**
         * 构造构建器。
         *
         * @param model 模型名
         */
        private Builder(String model) {
            this.model = model;
        }

        /**
         * 设置系统提示词。
         *
         * @param systemPrompt 系统提示词
         * @return 当前构建器
         */
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
        public Builder message(LlmMessage message) {
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
        public Builder messages(List<LlmMessage> messages) {
            if (messages != null) {
                this.messages.addAll(messages);
            }
            return this;
        }

        /**
         * 设置采样温度。
         *
         * @param temperature 采样温度
         * @return 当前构建器
         */
        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * 设置最大生成 token 数。
         *
         * @param maxTokens 最大生成 token 数
         * @return 当前构建器
         */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * 设置核采样概率。
         *
         * @param topP 核采样概率
         * @return 当前构建器
         */
        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * 设置停止序列。
         *
         * @param stop 停止序列
         * @return 当前构建器
         */
        public Builder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        /**
         * 设置可调用的工具定义。
         *
         * @param tools 工具定义
         * @return 当前构建器
         */
        public Builder tools(List<LlmTool> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * 设置工具选择策略。
         *
         * @param toolChoice 工具选择策略
         * @return 当前构建器
         */
        public Builder toolChoice(String toolChoice) {
            this.toolChoice = toolChoice;
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
         * 设置缓存保留策略。
         * <p>
         * <b>空串归一成 {@code null}</b>：与 {@link #cacheKey(String)} 同理，「设了一个空值」与
         * 「没设」在厂商侧是两回事。
         *
         * @param cacheRetention 保留策略，可为 {@code null}
         * @return 当前构建器
         */
        public Builder cacheRetention(String cacheRetention) {
            this.cacheRetention = cacheRetention == null || cacheRetention.trim().isEmpty()
                    ? null : cacheRetention.trim();
            return this;
        }

        /**
         * 设置缓存断点数。
         *
         * @param cacheBreakpoints 断点数，可为 {@code null}（未设置）
         * @return 当前构建器
         */
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
         * 构建不可变请求。
         *
         * @return 统一请求模型
         * @throws JellyfishException 模型名为空时抛出
         */
        public LlmRequest build() {
            if (model == null || model.trim().isEmpty()) {
                throw new JellyfishException("request model must not be blank");
            }
            return new LlmRequest(this);
        }
    }
}
