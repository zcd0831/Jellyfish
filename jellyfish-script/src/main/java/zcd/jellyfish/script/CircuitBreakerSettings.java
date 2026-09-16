package zcd.jellyfish.script;

import zcd.jellyfish.api.JellyfishException;

/**
 * 熔断参数：连续失败多少次打开、冷却多久、探测失败几轮转永久。
 * <p>
 * <b>为什么它不下发给网关</b>：熔断是宿主侧的事——只有宿主知道「这一门语言下有哪些脚本」、
 * 也只有宿主需要把「别再派发了」变成一个模型看得懂的错误。下发给网关就得让每种语言的网关
 * 各实现一遍同样的状态机，而三份实现里必然有两份会漂移；网关那边已有的「隔离卡死的 worker」
 * 是另一件事（进程活着与否），两者恰好都叫「隔离」而已。
 * <p>
 * <b>零有确定含义，不拒绝</b>：三个参数为 {@code 0} 时分别表示「不熔断」「立即重试」
 * 「永不转永久」。这与 {@link GatewaySettings} 的处理一致——零是合法配置，
 * 只有负数才是写错了。反过来把零当默认值会让「显式关闭熔断」这条唯一手段失效。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CircuitBreakerSettings {

    /** 默认连续失败次数上限：2 次就开始熔断，因为每一次都很贵（一个完整超时或一次进程重建）。 */
    public static final int DEFAULT_FAILURES_TO_OPEN = 2;

    /** 默认冷却秒数。 */
    public static final int DEFAULT_COOLDOWN_SECONDS = 60;

    /** 默认半开探测失败几轮后转永久。 */
    public static final int DEFAULT_ROUNDS_TO_PERMANENT = 3;

    /** 连续失败多少次打开熔断；{@code 0} 表示不熔断。 */
    private final int failuresToOpen;

    /** 冷却秒数；{@code 0} 表示下一次调用立即可以探测。 */
    private final int cooldownSeconds;

    /** 半开探测连续失败几轮后转永久；{@code 0} 表示永不转永久。 */
    private final int roundsToPermanent;

    /**
     * 构造设置。
     *
     * @param failuresToOpen    连续失败次数上限
     * @param cooldownSeconds   冷却秒数
     * @param roundsToPermanent 转永久的探测轮数上限
     */
    private CircuitBreakerSettings(int failuresToOpen, int cooldownSeconds, int roundsToPermanent) {
        this.failuresToOpen = failuresToOpen;
        this.cooldownSeconds = cooldownSeconds;
        this.roundsToPermanent = roundsToPermanent;
    }

    /**
     * 构造全部取默认值的设置。
     *
     * @return 默认设置
     */
    public static CircuitBreakerSettings defaults() {
        return builder().build();
    }

    /**
     * 构造设置构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取连续失败次数上限。
     *
     * @return 次数上限；{@code 0} 表示不熔断
     */
    public int failuresToOpen() {
        return failuresToOpen;
    }

    /**
     * 获取冷却秒数。
     *
     * @return 冷却秒数
     */
    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * 获取冷却毫秒数。
     *
     * @return 冷却毫秒数
     */
    public long cooldownMillis() {
        return cooldownSeconds * 1000L;
    }

    /**
     * 获取转永久的探测轮数上限。
     *
     * @return 轮数上限；{@code 0} 表示永不转永久
     */
    public int roundsToPermanent() {
        return roundsToPermanent;
    }

    @Override
    public String toString() {
        return "CircuitBreakerSettings{failuresToOpen=" + failuresToOpen
                + ", cooldown=" + cooldownSeconds + "s, roundsToPermanent=" + roundsToPermanent + '}';
    }

    /**
     * 设置构建器。
     * <p>
     * 用构建器而不是全参构造器：三个参数都是「非负整数」，位置写错在类型上完全看不出来，
     * 而它们的后果（熔断阈值与冷却时间对调）只有跑很久才暴露。
     * <p>
     * 非线程安全，仅供装配期单线程使用。
     *
     * @author zcd
     */
    public static final class Builder {

        /** 连续失败次数上限。 */
        private int failuresToOpen = DEFAULT_FAILURES_TO_OPEN;

        /** 冷却秒数。 */
        private int cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;

        /** 转永久的探测轮数上限。 */
        private int roundsToPermanent = DEFAULT_ROUNDS_TO_PERMANENT;

        /**
         * 常量类风格的私有构造器，仅允许 {@link CircuitBreakerSettings#builder()} 调用。
         */
        private Builder() {
        }

        /**
         * 设置连续失败次数上限。
         *
         * @param value 次数上限，不可为负
         * @return 本构建器
         * @throws JellyfishException 为负时抛出
         */
        public Builder failuresToOpen(int value) {
            this.failuresToOpen = requireNonNegative(value, "failuresToOpen");
            return this;
        }

        /**
         * 设置冷却秒数。
         *
         * @param value 秒数，不可为负
         * @return 本构建器
         * @throws JellyfishException 为负时抛出
         */
        public Builder cooldownSeconds(int value) {
            this.cooldownSeconds = requireNonNegative(value, "cooldownSeconds");
            return this;
        }

        /**
         * 设置转永久的探测轮数上限。
         *
         * @param value 轮数上限，不可为负
         * @return 本构建器
         * @throws JellyfishException 为负时抛出
         */
        public Builder roundsToPermanent(int value) {
            this.roundsToPermanent = requireNonNegative(value, "roundsToPermanent");
            return this;
        }

        /**
         * 构造设置。
         *
         * @return 设置
         */
        public CircuitBreakerSettings build() {
            return new CircuitBreakerSettings(failuresToOpen, cooldownSeconds, roundsToPermanent);
        }

        /**
         * 校验参数非负。
         *
         * @param value 待校验值
         * @param name  字段名，用于报错
         * @return 原值
         * @throws JellyfishException 为负时抛出
         */
        private static int requireNonNegative(int value, String name) {
            if (value < 0) {
                throw new JellyfishException("熔断参数 " + name + " 不得为负: " + value);
            }
            return value;
        }
    }
}
