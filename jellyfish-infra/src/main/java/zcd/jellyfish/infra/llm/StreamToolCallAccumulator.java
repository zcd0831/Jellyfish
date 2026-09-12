package zcd.jellyfish.infra.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式工具调用累加器（适用于 OpenAI 兼容协议与 Claude）。
 * <p>
 * 工具调用会按 index 分片下发：先 id/name，再分多次下发 arguments 片段。
 * 这里按 index 合并，并允许产出一份当前快照。
 *
 * @author zcd
 */
final class StreamToolCallAccumulator {

    /** 无 index 厂商（如 Gemini）伪造 key 的起始值，避开真实 index 的 0..n 区间。 */
    private static final int GENERATED_INDEX_BASE = 100000;

    /** 按分片 index 归并的工具调用，使用 LinkedHashMap 保证输出顺序稳定。 */
    private final Map<Integer, MutableToolCall> toolCalls = new LinkedHashMap<>();

    /** 无 index 厂商的伪造 key 自增值。 */
    private int generatedIndex = 0;

    /**
     * 合并一个带 index 的分片，返回合并后的快照。
     *
     * @param index          分片归属标识，为 {@code null} 时由内部生成
     * @param id             工具调用 id，可为 {@code null}
     * @param name           工具名，可为 {@code null}
     * @param argumentsDelta 参数片段，可为 {@code null}
     * @return 合并后的工具调用快照
     */
    LlmToolCall merge(Integer index, String id, String name, String argumentsDelta) {
        int key = index != null ? index : nextGeneratedIndex();
        MutableToolCall mutable = toolCalls.get(key);
        if (mutable == null) {
            mutable = new MutableToolCall(key);
            toolCalls.put(key, mutable);
        }
        if (id != null) {
            mutable.id = id;
        }
        if (name != null) {
            mutable.name = name;
        }
        if (argumentsDelta != null) {
            mutable.arguments.append(argumentsDelta);
        }
        return mutable.snapshot();
    }

    /**
     * 直接加入一个完整的工具调用（适用于一次下发完整参数的厂商）。
     *
     * @param id        工具调用 id
     * @param name      工具名
     * @param arguments 完整参数 JSON 字符串，可为 {@code null}
     * @return 新增的工具调用快照
     */
    LlmToolCall add(String id, String name, String arguments) {
        int key = nextGeneratedIndex();
        MutableToolCall mutable = new MutableToolCall(key);
        mutable.id = id;
        mutable.name = name;
        if (arguments != null) {
            mutable.arguments.append(arguments);
        }
        toolCalls.put(key, mutable);
        return mutable.snapshot();
    }

    /**
     * 判断是否尚未累计到任何工具调用。
     *
     * @return 无工具调用时返回 {@code true}
     */
    boolean isEmpty() {
        return toolCalls.isEmpty();
    }

    /**
     * 输出当前所有工具调用的快照列表。
     *
     * @return 工具调用列表，可能为空但不会为 {@code null}
     */
    List<LlmToolCall> toList() {
        List<LlmToolCall> result = new ArrayList<>(toolCalls.size());
        for (MutableToolCall mutable : toolCalls.values()) {
            result.add(mutable.snapshot());
        }
        return result;
    }

    /**
     * 生成下一个无 index 厂商的伪造 key。
     *
     * @return 伪造 key
     */
    private int nextGeneratedIndex() {
        return GENERATED_INDEX_BASE + generatedIndex++;
    }

    /**
     * 累加过程中的可变工具调用。
     *
     * @author zcd
     */
    private static final class MutableToolCall {

        /** 工具调用归属标识。 */
        private final Integer index;

        /** 工具调用 id，分片到达时逐步补齐。 */
        private String id;

        /** 工具名，分片到达时逐步补齐。 */
        private String name;

        /** 累计的参数片段。 */
        private final StringBuilder arguments = new StringBuilder();

        /**
         * 构造可变工具调用。
         *
         * @param index 工具调用归属标识
         */
        private MutableToolCall(Integer index) {
            this.index = index;
        }

        /**
         * 取当前状态的不可变快照。
         *
         * @return 工具调用快照
         */
        private LlmToolCall snapshot() {
            return new LlmToolCall(index, id, name, arguments.toString());
        }
    }
}
