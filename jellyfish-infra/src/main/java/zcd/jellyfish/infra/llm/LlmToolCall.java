package zcd.jellyfish.infra.llm;

/**
 * LLM 返回的一次工具（函数）调用。
 * <p>
 * 流式返回时，同一个工具调用的参数可能会分片到达，{@code arguments} 为当前已累计的片段，
 * {@code index} 用于将分片归属到同一个调用；非流式返回时 {@code arguments} 为完整 JSON。
 *
 * @author zcd
 */
public final class LlmToolCall {

    /** 流式分片归属标识；非流式或厂商未下发时为本地顺序号。 */
    private final Integer index;

    /** 工具调用 id，用于把工具结果回传给厂商（OpenAI / Claude 需要）。 */
    private final String id;

    /** 工具（函数）名。 */
    private final String name;

    /** 调用参数，JSON 字符串；流式时为当前已累计的片段。 */
    private final String arguments;

    /**
     * 构造一次工具调用。
     *
     * @param index     流式分片归属标识，可为 {@code null}
     * @param id        工具调用 id
     * @param name      工具名
     * @param arguments 参数 JSON 字符串
     */
    public LlmToolCall(Integer index, String id, String name, String arguments) {
        this.index = index;
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    /**
     * 获取流式分片归属标识。
     *
     * @return 分片标识，可能为 {@code null}
     */
    public Integer getIndex() {
        return index;
    }

    /**
     * 获取工具调用 id。
     *
     * @return 工具调用 id
     */
    public String getId() {
        return id;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取调用参数。
     *
     * @return 参数 JSON 字符串；流式时为当前已累计的片段
     */
    public String getArguments() {
        return arguments;
    }

    /**
     * 返回工具调用的可读表示，便于日志排查。
     *
     * @return 描述字符串
     */
    @Override
    public String toString() {
        return "LlmToolCall{" +
                "index=" + index +
                ", id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", arguments='" + arguments + '\'' +
                '}';
    }
}
