package zcd.jellyfish.api.llm;

/**
 * 传输层看到的一次工具（函数）调用。
 * <p>
 * <b>为什么流式也要给 {@code index}</b>：OpenAI 兼容协议会把参数按片段下发，同一次调用的片段只靠
 * {@code index} 归属。没有它，插件无法把「第 0 个调用的参数又来了两个字符」拼回同一处。
 * <p>
 * <b>{@code arguments} 是 JSON 字符串而不是对象</b>：流式途中它可能是<b>半个 JSON</b>，
 * 一个能反序列化的类型在多数时刻根本构造不出来。以字符串为口径，插件只需要做字符串拼接。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class LlmTransportToolCall {

    /** 流式分片归属标识；非流式或厂商未下发时可为 {@code null}。 */
    private final Integer index;

    /** 工具调用 id，回传工具结果时要带上。 */
    private final String id;

    /** 工具（函数）名。 */
    private final String name;

    /** 参数 JSON 字符串；流式时为当前已累计的片段。 */
    private final String arguments;

    /**
     * 构造工具调用。
     *
     * @param index     流式分片归属标识，可为 {@code null}
     * @param id        工具调用 id，可为 {@code null}
     * @param name      工具名，可为 {@code null}
     * @param arguments 参数 JSON 字符串，可为 {@code null}
     */
    public LlmTransportToolCall(Integer index, String id, String name, String arguments) {
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
     * @return 工具调用 id，可能为 {@code null}
     */
    public String getId() {
        return id;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名，可能为 {@code null}
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

    @Override
    public String toString() {
        return "LlmTransportToolCall{index=" + index + ", id=" + id + ", name=" + name
                + ", arguments=" + arguments + '}';
    }
}
