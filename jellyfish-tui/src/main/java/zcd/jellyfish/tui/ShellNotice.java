package zcd.jellyfish.tui;

import java.util.Objects;

/**
 * 一条外壳提示：命令结果 / 状态反馈，带产生时间戳。
 * <p>
 * <b>为什么必须是独立类型而不是直接存字符串</b>：提示要参与「与会话消息按时间归并」的投影，
 * 而排序键只有它自带时间戳才成立——字符串列表没有位置信息，只能整块贴在投影末尾，
 * 那正是「命令输出永远堆在屏幕底部、且排在比它更晚的对话之前」的成因（见
 * {@link TranscriptProjector#project}）。
 * <p>
 * <b>为什么不把提示塞进会话</b>：命令的副作用写回各自的域服务，「命令输出文本」不是会话消息；
 * 塞进会话会污染发给模型的历史（模型会以为自己说过 {@code /help} 的返回值）。
 * 因此它仍然由外壳持有，只是进入投影时按时间戳排到了正确位置。
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

    /**
     * 构造一条外壳提示。
     *
     * @param timestamp 提示产生时间戳（epoch millis）
     * @param command   触发本提示的命令原文，可为 {@code null} 或空白（不回显命令）
     * @param text      提示文本，不可为 {@code null}；可含 {@code '\n'}
     * @param kind      提示语义，不可为 {@code null}
     */
    public ShellNotice(long timestamp, String command, String text, Kind kind) {
        this.timestamp = timestamp;
        this.command = command;
        this.text = Objects.requireNonNull(text, "text must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
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
}
