package zcd.jellyfish.core.subagent;

import zcd.jellyfish.infra.session.SessionUsage;

/**
 * 一次子代理委派的结果：终态 + 子代理的最终文本 + 它花掉的用量。
 * <p>
 * <b>为什么带上用量而不是让调用方回查子会话</b>：子会话在委派结束后就被关掉了，
 * 而「这次委派花了多少」是父回合要如实告诉用户与账本的事实（见 {@link SubAgentLauncher}
 * 把用量归集到父会话）。等调用方想查时，那份数据已经不在了。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SubAgentOutcome {

    /** 终态。 */
    private final SubAgentStatus status;

    /** 子代理的最终文本；{@code CANCELLED} / {@code FAILED} / {@code REJECTED} 时为 {@code null}。 */
    private final String text;

    /** 子代理实际发生的轮数；未跑起来时为 0。 */
    private final int rounds;

    /** 子代理回合的累计用量，保证非 {@code null}。 */
    private final SessionUsage usage;

    /** 失败、拒绝或未收敛的原因；正常完成时为 {@code null}。 */
    private final String error;

    /**
     * 本次 run 归档文件的绝对路径；没有归档时为 {@code null}。
     * <p>
     * <b>为什么把它交出来</b>：归档里是子会话的完整 transcript，而 {@link #text} 只是它的有损摘要
     * （未收敛时甚至只有最后一段）。编排方拿到这个路径，就能在下游任务里附上一句「完整记录见
     * &lt;路径&gt;」，让接收方按需自取——与工具结果落盘后在信封里留路径是同一个口径。
     * <p>
     * <b>它可能为 {@code null}</b>：归档失败、拿不到 run 快照，或这次委派压根没跑起来（被拒）时都没有。
     * 归档是「可观测窗口」而不是不变量（受 {@code subAgent.archiveKeepFiles} /
     * {@code archiveMaxBytes} 约束，最旧的会被清理），因此调用方必须容忍它缺席。
     */
    private final String archivePath;

    /**
     * 构造结果。
     *
     * @param status      终态
     * @param text        最终文本，可为 {@code null}
     * @param rounds      实际轮数
     * @param usage       累计用量，可为 {@code null}（按零用量处理）
     * @param error       失败或拒绝的原因，可为 {@code null}
     * @param archivePath 归档文件路径，可为 {@code null}
     */
    private SubAgentOutcome(SubAgentStatus status, String text, int rounds, SessionUsage usage, String error,
                            String archivePath) {
        this.status = status;
        this.text = text;
        this.rounds = rounds;
        this.usage = usage == null ? SessionUsage.EMPTY : usage;
        this.error = error;
        this.archivePath = archivePath;
    }

    /**
     * 构造「完成」结果。
     *
     * @param text   子代理的最终文本，可为 {@code null}
     * @param rounds 实际轮数
     * @param usage  累计用量，可为 {@code null}
     * @return 结果
     */
    public static SubAgentOutcome completed(String text, int rounds, SessionUsage usage) {
        return new SubAgentOutcome(SubAgentStatus.COMPLETED, text, rounds, usage, null, null);
    }

    /**
     * 构造「未收敛」结果。
     * <p>
     * <b>{@code text} 与 {@code reason} 为什么都要带</b>：文本是给模型与编排方读的完整交代
     * （未收敛的原因 + 子代理最后一段已产出的正文），原因另存一份，好让调用方原样转述或做结构化
     * 元数据，而不必从文本里猜哪一段是内核说的、哪一段是子代理自己说的。
     *
     * @param text   未收敛的原因，外加子代理最后一段已产出的正文（它往往已经翻查过几轮）
     * @param rounds 实际轮数
     * @param usage  累计用量，可为 {@code null}
     * @param reason 未收敛的原因，可为 {@code null}
     * @return 结果
     */
    public static SubAgentOutcome truncated(String text, int rounds, SessionUsage usage, String reason) {
        return new SubAgentOutcome(SubAgentStatus.TRUNCATED, text, rounds, usage, reason, null);
    }

    /**
     * 构造「已取消」结果。
     *
     * @param rounds 已完成的轮数
     * @param usage  已花掉的用量，可为 {@code null}
     * @return 结果
     */
    public static SubAgentOutcome cancelled(int rounds, SessionUsage usage) {
        return new SubAgentOutcome(SubAgentStatus.CANCELLED, null, rounds, usage, null, null);
    }

    /**
     * 构造「执行失败」结果。
     *
     * @param error 失败原因
     * @return 结果
     */
    public static SubAgentOutcome failed(String error) {
        return new SubAgentOutcome(SubAgentStatus.FAILED, null, 0, null, error, null);
    }

    /**
     * 构造「未开始即被拒绝」结果。
     *
     * @param reason 拒绝原因，应当是调用方换个参数就能修好的那种
     * @return 结果
     */
    public static SubAgentOutcome rejected(String reason) {
        return new SubAgentOutcome(SubAgentStatus.REJECTED, null, 0, null, reason, null);
    }

    /**
     * 获取终态。
     *
     * @return 终态
     */
    public SubAgentStatus getStatus() {
        return status;
    }

    /**
     * 获取子代理的最终文本。
     *
     * @return 最终文本；取消、失败或被拒绝时为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取子代理实际发生的轮数。
     *
     * @return 轮数，未跑起来时为 0
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 获取子代理回合的累计用量。
     *
     * @return 累计用量，保证非 {@code null}
     */
    public SessionUsage getUsage() {
        return usage;
    }

    /**
     * 获取失败、拒绝或未收敛的原因。
     *
     * @return 原因；正常完成时为 {@code null}
     */
    public String getError() {
        return error;
    }

    /**
     * 获取本次 run 的归档文件绝对路径。
     *
     * @return 归档路径；没有归档时为 {@code null}
     */
    public String getArchivePath() {
        return archivePath;
    }

    /**
     * 返回一个带着归档路径的副本。
     * <p>
     * <b>为什么用追加而不是构造参数</b>：归档发生在结果算出来之后（{@code await} 的 {@code finally}
     * 里），而这里不可变——让 {@code SubAgentLauncher} 在拿到路径后补上，比把归档提前（那时 run
     * 还没终结、快照还是空的）更简单，也不改动五个工厂方法的签名。
     *
     * @param path 归档文件绝对路径，可为 {@code null}（等价于原样返回）
     * @return 副本，保证非 {@code null}
     */
    public SubAgentOutcome withArchivePath(String path) {
        return path == null ? this
                : new SubAgentOutcome(status, text, rounds, usage, error, path);
    }

    /**
     * 判断子代理是否给出了可用文本。
     * <p>
     * {@code TRUNCATED} 也算「有文本」：未收敛时，内核给出的原因后面还接着子代理最后一段
     * 已产出的正文，模型据此知道任务没做完、也知道它做到哪一步了，
     * 而不是以为子代理什么都没说。
     *
     * @return {@code COMPLETED} 或 {@code TRUNCATED} 返回 {@code true}
     */
    public boolean hasText() {
        return status == SubAgentStatus.COMPLETED || status == SubAgentStatus.TRUNCATED;
    }

    @Override
    public String toString() {
        return "SubAgentOutcome{status=" + status + ", rounds=" + rounds + '}';
    }
}
