package zcd.jellyfish.server.dto;

import java.util.List;

/**
 * SSE 载荷：一条插件通知（{@code shell_notice}）。
 * <p>
 * <b>为什么行是纯文本而不是 {@code UiSegment} 列表</b>：通知是<b>尽力 lane</b> 上的临时显示，
 * 它的价值在「什么时候说了一句什么」，不在行内强调。把 {@code UiSegment} 的
 * 种类 / 强调两个维度搬过线会让载荷从一层变成嵌套两层，而眼下没有任何客户端消费它
 * ——这与「不为想象中的需求预先付款」是同一条取舍。真要保留字形，等有客户端提出来再加一层。
 * <p>
 * <b>控制字符在服务端就已过滤</b>：文本来自插件，一个 {@code ESC} 序列足以改写客户端终端；
 * 过滤点必须在这一侧，因为客户端不一定会自己滤。
 * <p>
 * 不可变。
 *
 * @author zcd
 */
public final class ShellNoticeEvent {

    /** 贡献者 owner（插件标识或 {@code 插件标识::子标识}）。 */
    private final String owner;

    /** 目标会话标识；{@code SHELL} scope 的贡献为 {@code null}。 */
    private final String sessionId;

    /** 合并键；不做合并时为 {@code null}。 */
    private final String key;

    /** 严重程度：{@code INFO} / {@code WARN} / {@code ERROR}。 */
    private final String severity;

    /** 通知文本行（每行一段纯文本）。 */
    private final List<String> lines;

    /**
     * 构造载荷。
     *
     * @param owner     贡献者 owner
     * @param sessionId 目标会话标识
     * @param key       合并键
     * @param severity  严重程度
     * @param lines     文本行
     */
    public ShellNoticeEvent(String owner, String sessionId, String key, String severity, List<String> lines) {
        this.owner = owner;
        this.sessionId = sessionId;
        this.key = key;
        this.severity = severity;
        this.lines = lines;
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
     * 获取合并键。
     *
     * @return 合并键，可为 {@code null}
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取严重程度。
     *
     * @return 严重程度
     */
    public String getSeverity() {
        return severity;
    }

    /**
     * 获取文本行。
     *
     * @return 文本行，保证非 {@code null}
     */
    public List<String> getLines() {
        return lines;
    }
}
