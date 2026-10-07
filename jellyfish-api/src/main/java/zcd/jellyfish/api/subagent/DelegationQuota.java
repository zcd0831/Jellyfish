package zcd.jellyfish.api.subagent;

import zcd.jellyfish.api.JellyfishException;

/**
 * 此刻的派生额度：这一回合还能派出几个子代理。
 * <p>
 * <b>它回答的是「还能派多少」，而不是「能同时跑多少」</b>：额度按<b>顶层回合</b>累计，派生一个就少一个，
 * 与并发上限（超出的 run 排队等待，不算失败）是两回事。
 * <p>
 * <b>为什么把它交给插件</b>：一次编排要派几个子代理是它自己算得出来的（步骤数，外加汇总那一次），
 * 而「还能派几个」只有内核知道。没有这个数，插件只能在派生到一半时才发现额度用尽——那时钱已经花了，
 * 而且它会以为是自己写错了。拿到额度就能<b>在派生任何子代理之前</b>整份拒绝，并给出可执行的话
 * （拆成几次，或调大配置）。
 * <p>
 * <b>额度是快照，随时可能过时</b>：同一回合内别的调用方（例如 {@code task} 工具，或另一个编排）
 * 也会占用它。它足够支撑「该不该开始」的判断，不适合拿来做精确分配。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class DelegationQuota {

    /** 此刻还能派生的子代理数；{@code 0} 表示一个也派不了。 */
    private final int remainingSpawns;

    /** 一个也派不了时的原因；还能派时为 {@code null}。 */
    private final String blockedReason;

    /**
     * 构造额度。
     * <p>
     * 跨边界值类型只有这一个可见构造器（Jackson 反序列化要求）；日常构造请用静态工厂。
     *
     * @param remainingSpawns 还能派生的子代理数，不可为负
     * @param blockedReason   派不了的原因，可为 {@code null}
     * @throws JellyfishException 剩余额度为负时抛出
     */
    public DelegationQuota(int remainingSpawns, String blockedReason) {
        if (remainingSpawns < 0) {
            throw new JellyfishException("remainingSpawns must not be negative: " + remainingSpawns);
        }
        this.remainingSpawns = remainingSpawns;
        this.blockedReason = blockedReason;
    }

    /**
     * 构造「还能派」的额度。
     *
     * @param remainingSpawns 还能派生的子代理数，不可为负
     * @return 额度，保证非 {@code null}
     */
    public static DelegationQuota of(int remainingSpawns) {
        return new DelegationQuota(remainingSpawns, null);
    }

    /**
     * 构造「一个也派不了」的额度。
     * <p>
     * 原因要写成一句可执行的话：拿到它的人得知道该改什么，而不只是「不能派」。
     *
     * @param reason 派不了的原因，应当是可读的一句话
     * @return 额度，保证非 {@code null}
     */
    public static DelegationQuota blocked(String reason) {
        return new DelegationQuota(0, reason);
    }

    /**
     * 获取此刻还能派生的子代理数。
     *
     * @return 剩余额度，保证非负
     */
    public int getRemainingSpawns() {
        return remainingSpawns;
    }

    /**
     * 获取「一个也派不了」的原因。
     *
     * @return 原因；还能派时为 {@code null}
     */
    public String getBlockedReason() {
        return blockedReason;
    }

    /**
     * 判断此刻还能不能派出子代理。
     *
     * @return 一个也派不了时返回 {@code true}
     */
    public boolean isBlocked() {
        return remainingSpawns <= 0;
    }

    @Override
    public String toString() {
        return isBlocked()
                ? "DelegationQuota{blocked=" + blockedReason + '}'
                : "DelegationQuota{remaining=" + remainingSpawns + '}';
    }
}
