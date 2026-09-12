package zcd.jellyfish.infra.event;

/**
 * 配置告警事件：用于「配置缺失或结构可疑但不中断启动」的场景，由 {@link EventBus} 异步广播。
 * <p>
 * 配置层不直接依赖日志实现，只发出事件；具体如何呈现（日志、TUI 提示、指标）由订阅方决定。
 *
 * @author zcd
 */
public class ConfigWarningEvent {

    /** 配置来源，通常是文件路径或配置段名。 */
    private final String source;

    /** 告警描述。 */
    private final String message;

    /**
     * 构造告警事件。
     *
     * @param source  配置来源，可为 {@code null}
     * @param message 告警描述
     */
    public ConfigWarningEvent(String source, String message) {
        this.source = source;
        this.message = message;
    }

    /**
     * 获取配置来源。
     *
     * @return 配置来源，未提供时为 {@code null}
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取告警描述。
     *
     * @return 告警描述
     */
    public String getMessage() {
        return message;
    }
}
