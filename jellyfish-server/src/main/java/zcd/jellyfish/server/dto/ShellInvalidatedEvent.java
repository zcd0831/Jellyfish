package zcd.jellyfish.server.dto;

/**
 * SSE 载荷：一条插件失效提示（{@code shell_invalidated}）。
 * <p>
 * <b>它只是提示，不是协议</b>：{@code what} 是自由文本，客户端可以忽略它并全量重拉
 * （见 {@code ShellContribution}）。把它当跨边界标识符会让插件与客户端必须对同一套取值。
 * <p>
 * 不可变。
 *
 * @author zcd
 */
public final class ShellInvalidatedEvent {

    /** 贡献者 owner。 */
    private final String owner;

    /** 目标会话标识；{@code SHELL} scope 的贡献为 {@code null}。 */
    private final String sessionId;

    /** 定位线索；{@code null} 表示「该插件的全部贡献都脏了」。 */
    private final String what;

    /**
     * 构造载荷。
     *
     * @param owner     贡献者 owner
     * @param sessionId 目标会话标识
     * @param what      定位线索
     */
    public ShellInvalidatedEvent(String owner, String sessionId, String what) {
        this.owner = owner;
        this.sessionId = sessionId;
        this.what = what;
    }

    /**
     * 获取贡献者 owner。
     *
     * @return owner
     */
    public String getOwner() {
        return owner;
    }

    /**
     * 获取目标会话标识。
     *
     * @return 会话标识，可为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取定位线索。
     *
     * @return 线索，可为 {@code null}
     */
    public String getWhat() {
        return what;
    }
}
