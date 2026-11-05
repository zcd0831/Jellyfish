package io.github.zcd0831.jellyfish.domain.perception.session;

/**
 * 一条待追加记录的"内容部分"：调用方知道的东西（谁说的、说了什么、属于哪次响应…），
 * 不含 `id` / `sequence` / `date` —— 那三项由会话在追加时统一编号，调用方无从干预
 * （04-perception §4.8.1 规则 5「只追加」、规则 14「同一响应共享 turnId」）。
 * <p>
 * 用 builder 而不是 10 个位置参数：可选字段太多，位置一错就是"reasoning 写进了 turnId"这种
 * 只有回放时才暴露的错，编译器看不出来。
 */
public final class RecordDraft {

    private final RecordRole role;
    private final String content;
    private final String reasoning;
    private final String turnId;
    private final String producerModelId;
    private final ToolResultPayload toolPayload;
    private final int summaryCoversUpTo;

    private RecordDraft(Builder builder) {
        this.role = builder.role;
        this.content = builder.content;
        this.reasoning = builder.reasoning;
        this.turnId = builder.turnId;
        this.producerModelId = builder.producerModelId;
        this.toolPayload = builder.toolPayload;
        this.summaryCoversUpTo = builder.summaryCoversUpTo;
    }

    /** 必填的两项放在入口上，其余按需链式设置。 */
    public static Builder builder(RecordRole role, String content) {
        return new Builder(role, content);
    }

    public static final class Builder {

        private final RecordRole role;
        private final String content;
        private String reasoning;
        private String turnId;
        private String producerModelId;
        private ToolResultPayload toolPayload;
        private int summaryCoversUpTo;

        private Builder(RecordRole role, String content) {
            if (role == null) {
                throw new IllegalArgumentException("记录角色不能为空");
            }
            this.role = role;
            this.content = content;
        }

        /** 模型私有思维状态：只回放给产者（规则 15）。 */
        public Builder reasoning(String value) {
            this.reasoning = value;
            return this;
        }

        /** 一次模型响应的标识：同一响应的记录共享（规则 14/17）。 */
        public Builder turnId(String value) {
            this.turnId = value;
            return this;
        }

        public Builder producerModelId(String value) {
            this.producerModelId = value;
            return this;
        }

        public Builder toolPayload(ToolResultPayload value) {
            this.toolPayload = value;
            return this;
        }

        /** 只对 SUMMARY 有意义：覆盖到（含）哪条记录的 sequence（规则 9/11）。 */
        public Builder summaryCoversUpTo(int value) {
            this.summaryCoversUpTo = value;
            return this;
        }

        public RecordDraft build() {
            return new RecordDraft(this);
        }
    }

    RecordRole role() {
        return role;
    }

    String content() {
        return content;
    }

    String reasoning() {
        return reasoning;
    }

    String turnId() {
        return turnId;
    }

    String producerModelId() {
        return producerModelId;
    }

    ToolResultPayload toolPayload() {
        return toolPayload;
    }

    int summaryCoversUpTo() {
        return summaryCoversUpTo;
    }
}
