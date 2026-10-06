package zcd.jellyfish.tui;

import java.util.Objects;

/**
 * 一条外壳提示：插件通知或状态反馈，带产生时间戳。
 * <p>
 * <b>为什么必须是独立类型而不是直接存字符串</b>：提示要参与「与会话消息按时间归并」的投影，
 * 而排序键只有它自带时间戳才成立——字符串列表没有位置信息，只能整块贴在投影末尾，
 * 那正是「提示永远堆在屏幕底部、且排在比它更晚的对话之前」的成因（见
 * {@link TranscriptProjector#project}）。
 * <p>
 * <b>为什么不把提示塞进会话</b>：提示是外壳状态而不是会话消息；塞进会话会污染发给模型的历史
 * （模型会以为自己说过那条内容）。因此它由外壳持有，只是进入投影时按时间戳排到了正确位置。
 * <p>
 * <b>命令结果不走这条通道</b>：它进 {@code ShellOutput}（命令输出面板——只留最近一次、可关可滚）。
 * 两者的生命周期不同：这里是「按时间发生的事」，那里是「刚才敲下那一下的回应」。
 * <p>
 * <b>插件通知就走这里</b>（见 {@link #plugin}）：插件推的
 * {@code ShellContribution} 是「外壳状态」而不是会话消息，带 {@code owner} 的那种
 * 能按来源做显示条数淘汰（一个插件不该把屏幕刷满）。
 * <p>
 * 不可变：所有字段 final、无 setter。
 *
 * @author zcd
 */
public final class ShellNotice {

    /**
     * 提示语义三态：决定投影时的前缀与颜色。
     * <p>
     * 与 {@link zcd.jellyfish.api.extension.CommandResult.Kind} 刻意分开：本枚举要覆盖外壳自己产生的
     * 反馈（如「回合进行中」），那些根本没有命令结果；两者的映射在 {@link TuiApp} 里显式写出。
     */
    public enum Kind {

        /** 正常结果 / 状态反馈。 */
        INFO,

        /** 需要留意但不致命（例如输入了不存在的命令）。 */
        WARN,

        /** 失败。 */
        ERROR
    }

    /** 提示产生时间戳（epoch millis），用于与会话消息排序。 */
    private final long timestamp;

    /** 触发本提示的命令原文，可为 {@code null}（状态反馈没有命令可回显）。 */
    private final String command;

    /** 提示文本，可含 {@code '\n'}。 */
    private final String text;

    /** 提示语义。 */
    private final Kind kind;

    /** 来源 owner（插件标识或 {@code 插件标识::子标识}），可为 {@code null}（外壳自己产生的提示）。 */
    private final String owner;

    /**
     * 构造一条外壳提示。
     *
     * @param timestamp 提示产生时间戳（epoch millis）
     * @param command   触发本提示的命令原文，可为 {@code null} 或空白（不回显命令）
     * @param text      提示文本，不可为 {@code null}；可含 {@code '\n'}
     * @param kind      提示语义，不可为 {@code null}
     */
    public ShellNotice(long timestamp, String command, String text, Kind kind) {
        this(timestamp, null, command, text, kind);
    }

    /**
     * 构造一条提示（全字段）。
     * <p>
     * <b>它保持私有</b>：新增字段走「一个私有重载 + 一个新静态工厂」，而不是再开一个公开构造器。
     * 这条纪律见 {@code constraints/shells.md}——快照类一旦多出可见构造器，
     * 调用方就会开始依赖某一种组合，而它们之间的差异是不可见的。
     *
     * @param timestamp 时间戳
     * @param owner     来源 owner，可为 {@code null}
     * @param command   触发本提示的命令原文，可为 {@code null}
     * @param text      提示文本，不可为 {@code null}
     * @param kind      提示语义，不可为 {@code null}
     */
    private ShellNotice(long timestamp, String owner, String command, String text, Kind kind) {
        this.timestamp = timestamp;
        this.owner = owner;
        this.command = command;
        this.text = Objects.requireNonNull(text, "text must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    /**
     * 构造一条插件来源的提示。
     * <p>
     * <b>没有 {@code command}</b>：插件没有「敲了什么」这件事可回显，硬塞一个 owner 进去
     * 会被渲染成用户输入行（{@code >} 前缀），那是误导。来源归因交给
     * {@link #getOwner()}，由 {@code ChatState} 用于「同一来源最多显示几条」的淘汰。
     *
     * @param timestamp 时间戳（epoch millis）
     * @param owner     来源 owner，不可为空白
     * @param text      提示文本，不可为 {@code null} 或空白；可含 {@code '\n'}
     * @param kind      提示语义，不可为 {@code null}
     * @return 提示，保证非 {@code null}
     * @throws IllegalArgumentException owner 为空白或 text 为 {@code null} 时抛出
     */
    public static ShellNotice plugin(long timestamp, String owner, String text, Kind kind) {
        if (owner == null || owner.trim().isEmpty()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        return new ShellNotice(timestamp, owner, null, Objects.requireNonNull(text, "text must not be null"), kind);
    }

    /**
     * 获取提示产生时间戳。
     *
     * @return 时间戳（epoch millis）
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 获取触发本提示的命令原文。
     *
     * @return 命令原文；没有命令可回显时返回 {@code null}
     */
    public String getCommand() {
        return command;
    }

    /**
     * 获取提示文本。
     *
     * @return 提示文本，保证非 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取提示语义。
     *
     * @return 提示语义，保证非 {@code null}
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 获取来源 owner。
     *
     * @return 来源 owner；外壳自己产生的提示返回 {@code null}
     */
    public String getOwner() {
        return owner;
    }
}
