package zcd.jellyfish.api.subagent;

/**
 * 一次委派的结果：终态 + 最终文本 + 用量。
 * <p>
 * <b>为什么带轮数与 token</b>：编排方要能把「这一批花了多少」如实回报出去（判断要不要继续扇出），
 * 而这些数字只有内核知道。它们是<b>该 run 自己</b>的用量；父会话账本上的归集由内核完成，
 * 与本结果无关。
 * <p>
 * <b>{@link #getError()} 是给编排方看的，不是给用户看的</b>：它的措辞面向「下一步怎么办」
 * （这就是为什么被拒时会明说「开关关闭」而不是「失败」）。编排方若要回报给模型，
 * 应当自己组织一段说明；直接把它当成最终答复通常不是好主意。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class DelegationResult {

    /** run 标识；委派未开始（{@link DelegationStatus#REJECTED}）时为 {@code null}。 */
    private final String runId;

    /** 终态。 */
    private final DelegationStatus status;

    /** 子代理的最终文本，可为 {@code null}。 */
    private final String text;

    /** 实际轮数。 */
    private final int rounds;

    /** 累计 token 用量。 */
    private final long totalTokens;

    /** 失败或拒绝的原因，可为 {@code null}。 */
    private final String error;

    /**
     * 本次 run 归档文件的绝对路径；没有归档时为 {@code null}。
     * <p>
     * <b>它是「正文之外还有什么」的那条线索</b>：{@link #getText()} 是有损的（未收敛时可能只含
     * 最后一段），而归档里有子会话的完整 transcript。编排方据此可以在下游任务里附上
     * 「完整记录见 &lt;路径&gt;」，让接收方按需自取——与工具结果落盘后在信封里留路径同一口径。
     * <p>
     * <b>随时可能缺席</b>：被拒 / 未开始、归档失败、拿不到 run 快照时都是 {@code null}；归档本身
     * 还受文件数与字节数上限约束（最旧的会被清理），因此调用方必须容忍它读不到。
     */
    private final String archivePath;

    /**
     * 构造结果。
     * <p>
     * 跨边界值类型只有这一个可见构造器（Jackson 反序列化要求）；日常构造请用静态工厂。
     *
     * @param runId       run 标识，可为 {@code null}
     * @param status      终态，不可为 {@code null}
     * @param text        最终文本，可为 {@code null}
     * @param rounds      实际轮数
     * @param totalTokens 累计 token 用量
     * @param error       失败或拒绝的原因，可为 {@code null}
     * @param archivePath 归档文件路径，可为 {@code null}
     */
    public DelegationResult(String runId, DelegationStatus status, String text, int rounds, long totalTokens,
                            String error, String archivePath) {
        this.runId = runId;
        this.status = status;
        this.text = text;
        this.rounds = rounds;
        this.totalTokens = totalTokens;
        this.error = error;
        this.archivePath = archivePath;
    }

    /**
     * 构造「子代理给出最终回复」的结果。
     *
     * @param runId       run 标识
     * @param text        最终文本，可为 {@code null}
     * @param rounds      实际轮数
     * @param totalTokens 累计 token 用量
     * @return 结果，保证非 {@code null}
     */
    public static DelegationResult completed(String runId, String text, int rounds, long totalTokens) {
        return new DelegationResult(runId, DelegationStatus.COMPLETED, text, rounds, totalTokens, null, null);
    }

    /**
     * 构造「达到轮数上限仍未收敛」的结果。
     *
     * @param runId       run 标识
     * @param text        内核给的提示文本，可为 {@code null}
     * @param rounds      实际轮数
     * @param totalTokens 累计 token 用量
     * @return 结果，保证非 {@code null}
     */
    public static DelegationResult truncated(String runId, String text, int rounds, long totalTokens) {
        return new DelegationResult(runId, DelegationStatus.TRUNCATED, text, rounds, totalTokens, null, null);
    }

    /**
     * 构造「被取消」的结果。
     *
     * @param runId       run 标识
     * @param rounds      实际轮数
     * @param totalTokens 累计 token 用量
     * @return 结果，保证非 {@code null}
     */
    public static DelegationResult cancelled(String runId, int rounds, long totalTokens) {
        return new DelegationResult(runId, DelegationStatus.CANCELLED, null, rounds, totalTokens, null, null);
    }

    /**
     * 构造「已经开始但失败」的结果。
     *
     * @param runId run 标识，可为 {@code null}
     * @param error 失败原因，可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static DelegationResult failed(String runId, String error) {
        return new DelegationResult(runId, DelegationStatus.FAILED, null, 0, 0L, error, null);
    }

    /**
     * 构造「根本没有开始」的结果。
     *
     * @param error 拒绝原因，可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static DelegationResult rejected(String error) {
        return new DelegationResult(null, DelegationStatus.REJECTED, null, 0, 0L, error, null);
    }

    /**
     * 获取 run 标识。
     *
     * @return run 标识；委派未开始时为 {@code null}
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取终态。
     *
     * @return 终态，保证非 {@code null}
     */
    public DelegationStatus getStatus() {
        return status;
    }

    /**
     * 获取最终文本。
     *
     * @return 最终文本，可能为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取实际轮数。
     *
     * @return 轮数，保证非负
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 获取累计 token 用量。
     *
     * @return token 用量，保证非负
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * 获取失败或拒绝的原因。
     *
     * @return 原因，可能为 {@code null}
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
     *
     * @param path 归档文件绝对路径，可为 {@code null}（等价于原样返回）
     * @return 副本，保证非 {@code null}
     */
    public DelegationResult withArchivePath(String path) {
        return path == null ? this
                : new DelegationResult(runId, status, text, rounds, totalTokens, error, path);
    }

    /**
     * 判断是否拿到了可用文本。
     *
     * @return 文本非空白时返回 {@code true}
     */
    public boolean hasText() {
        return text != null && !text.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "DelegationResult{runId=" + runId + ", status=" + status + ", rounds=" + rounds
                + ", totalTokens=" + totalTokens + '}';
    }
}
