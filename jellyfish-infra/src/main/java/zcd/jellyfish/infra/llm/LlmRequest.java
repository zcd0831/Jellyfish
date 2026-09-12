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
