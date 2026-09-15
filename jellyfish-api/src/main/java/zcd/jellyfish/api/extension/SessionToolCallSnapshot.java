package zcd.jellyfish.api.extension;

/**
 * 会话消息里的一次工具调用快照。
 * <p>
 * {@code arguments} 保持厂商返回的<b>原始 JSON 文本</b>，不解析成映射：这一层只负责把消息原样带出去
 * 再原样带回来，解析是模型调用侧的事；在这里解析一次、回放时再拼一次，只会在两者之间引入
 * 「序列化不一致」的可能。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionToolCallSnapshot {

    /** 工具调用在本次响应中的序号，未提供时为 {@code null}。 */
    private final Integer index;

    /** 工具调用标识，用于与工具结果消息配对。 */
    private final String id;

    /** 工具名。 */
    private final String name;

    /** 工具参数原始 JSON 文本，可为 {@code null}。 */
    private final String arguments;

    /**
     * 构造工具调用快照。
     *
     * @param index     序号，可为 {@code null}
     * @param id        工具调用标识，可为 {@code null}
     * @param name      工具名，可为 {@code null}
     * @param arguments 参数原始 JSON 文本，可为 {@code null}
     */
    public SessionToolCallSnapshot(Integer index, String id, String name, String arguments) {
        this.index = index;
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    /**
     * 获取工具调用序号。
     *
     * @return 序号，未提供时为 {@code null}
     */
    public Integer getIndex() {
        return index;
    }

    /**
     * 获取工具调用标识。
     *
     * @return 工具调用标识，可为 {@code null}
     */
    public String getId() {
        return id;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名，可为 {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * 获取工具参数原始 JSON 文本。
     *
     * @return 参数 JSON 文本，可为 {@code null}
     */
    public String getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "SessionToolCallSnapshot{id=" + id + ", name=" + name + '}';
    }
}
