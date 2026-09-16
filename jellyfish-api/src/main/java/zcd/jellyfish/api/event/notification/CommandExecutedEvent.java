package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;
import zcd.jellyfish.api.extension.CommandResult;

/**
 * 命令审计事件：每次命令分发结束后广播，{@code OK} / {@code ERROR} / {@code UNKNOWN} <b>都发</b>。
 * <p>
 * 与 {@code PermissionDecidedEvent} 同口径：只观察、不参与分发，订阅方拿到的是已生效的结果。
 * 「未知命令」同样必须可见——「谁敲了什么但内核没有这条命令」本身就是审计信息，
 * 漏掉它会让「用户以为执行了」这类问题查不出来。
 * <p>
 * <b>只在真正走过分发时广播</b>：语法上不是命令的输入、以及只读的候选查询（{@code options}）
 * 都不产生本事件。否则「审计」与「用户敲了什么」会被混为一谈。
 * <p>
 * <b>不带命令输出文本</b>：输出可能很长、也可能包含正文内容，审计只需要「谁在什么会话里执行了什么、
 * 结果如何、耗时多久」。原始输入（含参数）与命中的处理器来源在这里，输出由调用方自己拿。
 * <p>
 * 事件通道是 best-effort（有界队列、队列满即丢弃），因此本事件用于可观测性，
 * 不承担审计级的可靠性保证；需要不可丢的落盘审计必须另开同步通道。
 *
 * @author zcd
 */
public final class CommandExecutedEvent extends AbstractJellyfishEvent {

    /** 用户输入原文（原文入口）；结构化入口没有原文，为 {@code null}。 */
    private final String input;

    /** 用户给出的名字（命令名或别名）。 */
    private final String name;

    /** 命中的命令名；未命中任何命令时为 {@code null}。 */
    private final String canonicalName;

    /** 分发结果三态。 */
    private final CommandResult.Kind kind;

    /** 处理器的来源（{@code "core"} 或 pluginId）；未命中或未取到处理器时为 {@code null}。 */
    private final String source;

    /** 分发耗时（毫秒）。 */
    private final long durationMillis;

    /**
     * 构造命令审计事件。
     *
     * @param input          用户输入原文，可为 {@code null}
     * @param name           用户给出的名字（命令名或别名），不可为 {@code null}
     * @param canonicalName  命中的命令名，可为 {@code null}
     * @param kind           分发结果三态，不可为 {@code null}
     * @param source         处理器来源（{@code "core"} 或 pluginId），可为 {@code null}
     * @param durationMillis 分发耗时（毫秒）
     * @param sessionId      会话标识，可为 {@code null}
     */
    public CommandExecutedEvent(String input, String name, String canonicalName, CommandResult.Kind kind,
                                String source, long durationMillis, String sessionId) {
        super(sessionId);
        this.input = input;
        this.name = name;
        this.canonicalName = canonicalName;
        this.kind = kind;
        this.source = source;
        this.durationMillis = durationMillis;
    }

    /**
     * 获取用户输入原文。
     *
     * @return 原文；结构化入口为 {@code null}
     */
    public String getInput() {
        return input;
    }

    /**
     * 获取用户给出的名字。
     *
     * @return 命令名或别名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取命中的命令名。
     *
     * @return 命令名；未命中时为 {@code null}
     */
    public String getCanonicalName() {
        return canonicalName;
    }

    /**
     * 获取分发结果三态。
     *
     * @return 结果三态
     */
    public CommandResult.Kind getKind() {
        return kind;
    }

    /**
     * 获取处理器来源。
     *
     * @return {@code "core"} 或 pluginId；未取到时为 {@code null}
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取分发耗时。
     *
     * @return 耗时（毫秒）
     */
    public long getDurationMillis() {
        return durationMillis;
    }
}
