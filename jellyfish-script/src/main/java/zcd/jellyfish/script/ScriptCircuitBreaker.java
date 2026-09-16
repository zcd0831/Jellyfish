package zcd.jellyfish.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.util.function.LongSupplier;

/**
 * 单个脚本的熔断状态机：连续失败到阈值就拒绝派发，冷却后自动半开探测。
 * <p>
 * <b>为什么是「不摘注册」而不是「摘注册」</b>：注册与进程生命周期解耦（清单驱动、懒启动）是这套设计
 * 的核心价值。摘注册会把这个价值丢掉——注册只能在插件的 {@code start()} 窗口内发生，
 * 因此恢复就必须走 {@code /reload}，而那是一次「重新读配置、重启插件」的重动作。
 * 熔断保住的是<b>自动半开恢复</b>：工具仍然在清单里，模型可能反复调用，
 * 但它拿到的是一个明确的、带剩余时间的错误，看到一次就会避开。
 * <p>
 * <b>半开期不限制并发探测数</b>：限制「只放一个」需要排队或计数，代价是把调用线程挂住；
 * 而多放几次探测的代价只是多几次失败。因此这里让并发探测都过去，
 * 状态由最后到达的那个结果决定——它是自纠正的，下一次失败会立刻重新打开。
 * <p>
 * <b>所有方法都是同步的</b>：调用可能来自任意线程（{@code ReAct} 线程池），
 * 而这里的临界区只有一个计数器与一个枚举，锁的争用可以忽略。
 * <p>
 * 不可变的是身份与阈值，可变的是状态；实例本身线程安全。
 *
 * @author zcd
 */
public final class ScriptCircuitBreaker {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptCircuitBreaker.class);

    /** 熔断状态。 */
    public enum State {

        /** 正常放行。 */
        CLOSED("正常"),

        /** 拒绝派发，冷却后自动转半开。 */
        OPEN("熔断中"),

        /** 放行探测，成功即恢复、失败则重新打开。 */
        HALF_OPEN("探测中"),

        /** 拒绝派发且不再自动恢复，只能靠 {@code /reload} 重建。 */
        PERMANENT("已停止调用");

        /** 中文名，用于状态命令展示。 */
        private final String displayName;

        /**
         * 构造状态。
         *
         * @param displayName 中文名
         */
        State(String displayName) {
            this.displayName = displayName;
        }

        /**
         * 获取中文名。
         *
         * @return 中文名
         */
        public String displayName() {
            return displayName;
        }
    }

    /** 脚本标识。 */
    private final String scriptId;

    /** 熔断参数。 */
    private final CircuitBreakerSettings settings;

    /** 状态变化观察者。 */
    private final ScriptCircuitListener listener;

    /** 当前时间源，测试用于推进冷却。 */
    private final LongSupplier clock;

    /** 当前状态。 */
    private State state = State.CLOSED;

    /** CLOSED 下的连续失败数。 */
    private int consecutiveFailures;

    /** 已连续失败了几轮探测。 */
    private int openRounds;

    /** 最近一次打开的时刻。 */
    private long openedAtMillis;

    /**
     * 构造熔断器，时间源取系统时钟。
     *
     * @param scriptId 脚本标识，不可为空白
     * @param settings 熔断参数，不可为 {@code null}
     * @param listener 状态变化观察者，可为 {@code null}
     */
    public ScriptCircuitBreaker(String scriptId, CircuitBreakerSettings settings,
                               ScriptCircuitListener listener) {
        this(scriptId, settings, listener, System::currentTimeMillis);
    }

    /**
     * 构造熔断器，并指定时间源。
     * <p>
     * 时间源可注入是为了让「冷却 60 秒后自动半开」这条转移能在单测里被验证：
     * 用真实时钟验它只能靠 {@code Thread.sleep}，那会让单测变慢且不稳定。
     *
     * @param scriptId 脚本标识，不可为空白
     * @param settings 熔断参数，不可为 {@code null}
     * @param listener 状态变化观察者，可为 {@code null}
     * @param clock    当前时间源（毫秒），不可为 {@code null}
     */
    ScriptCircuitBreaker(String scriptId, CircuitBreakerSettings settings,
                         ScriptCircuitListener listener, LongSupplier clock) {
        if (scriptId == null || scriptId.trim().isEmpty()) {
            throw new JellyfishException("熔断器的脚本标识不可为空白");
        }
        if (settings == null) {
            throw new JellyfishException("熔断参数不可为 null");
        }
        if (clock == null) {
            throw new JellyfishException("时间源不可为 null");
        }
        this.scriptId = scriptId;
        this.settings = settings;
        this.listener = listener;
        this.clock = clock;
    }

    /**
     * 判断本次调用是否可以派发。
     * <p>
     * 它有副作用：冷却到期时会把自己推进到 {@link State#HALF_OPEN}——「要不要探测」与
     * 「探测什么时候开始」本来就是同一件事，拆成两个方法只会让调用点可能忘了推进状态。
     *
     * @return 放行返回 {@code true}
     */
    public synchronized boolean admit() {
        if (state == State.PERMANENT) {
            return false;
        }
        if (state == State.OPEN) {
            if (clock.getAsLong() - openedAtMillis < settings.cooldownMillis()) {
                return false;
            }
            state = State.HALF_OPEN;
        }
        return true;
    }

    /**
     * 记录一次成功。
     * <p>
     * 半开下的成功即恢复（回到 {@link State#CLOSED} 并清零计数）；正常态下的成功只是清零连续失败。
     */
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN || state == State.OPEN) {
            // OPEN 下也可能走到这里：调用是在打开之前被放行的，结果晚于打开时刻才回来。
            // 把它当成恢复的证据是合理的——脚本刚刚确实答复了
            state = State.CLOSED;
            consecutiveFailures = 0;
            int rounds = openRounds;
            openRounds = 0;
            // 恢复也通知：打开那天发了告警，恢复就必须也有一条，否则日志里留下的是
            // 「某个工具坏了」，而它其实已经好了
            notifyRecovered(rounds);
            return;
        }
        consecutiveFailures = 0;
    }

    /**
     * 记录一次失败。
     * <p>
     * 正常态下累加计数，到阈值就打开；半开下的失败算一轮探测失败，
     * 达上限转永久，否则重新打开并重新计时。
     */
    public synchronized void recordFailure() {
        if (state == State.PERMANENT || state == State.OPEN) {
            // 打开期间失败不再累加：熔断期间本来就没派发新请求，此时到达的失败属于
            // 「打开之前放行的那一次」。让它顺延冷却时间会让熔断变得不可预测——
            // 剩余时间忽长忽短，而用户唯一的线索就是这个数字
            return;
        }
        if (state == State.HALF_OPEN) {
            openRounds++;
            if (settings.roundsToPermanent() > 0 && openRounds >= settings.roundsToPermanent()) {
                state = State.PERMANENT;
                notifyOpened("连续 " + openRounds + " 轮探测失败，已停止调用（修改脚本或配置后执行 /reload 恢复）");
                return;
            }
            openAt("第 " + openRounds + " 轮探测失败");
            return;
        }
        consecutiveFailures++;
        if (settings.failuresToOpen() > 0 && consecutiveFailures >= settings.failuresToOpen()) {
            openAt("连续 " + consecutiveFailures + " 次失败");
        }
    }

    /**
     * 获取当前状态。
     * <p>
     * <b>它是纯粹的观察，不复用 {@link #admit()}</b>：后者会推进状态，而「看一眼状态」不该改变它。
     * 因此这里返回的是<b>已经发生的事实</b>——{@link State#HALF_OPEN} 的含义是「已经放行过一次探测」，
     * 而不是「冷却已到期」。冷却到期的 {@link State#OPEN} 仍然是 {@code OPEN}，
     * 读 {@link #retryAfterMillis()} 会得到 {@code 0}，展示层据此写成「随时可探测」。
     * 把这个区别留在状态里而不是让读状态的方法替调用方推进，是为了让「谁在什么时候动了状态机」
     * 始终只有 {@link #admit()} / {@link #recordSuccess()} / {@link #recordFailure()} 三个答案。
     *
     * @return 当前状态
     */
    public synchronized State state() {
        return state;
    }

    /**
     * 获取连续失败次数。
     *
     * @return 连续失败次数
     */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * 获取已失败了几轮探测。
     *
     * @return 探测失败轮数
     */
    public synchronized int openRounds() {
        return openRounds;
    }

    /**
     * 获取脚本标识。
     *
     * @return 脚本标识
     */
    public String scriptId() {
        return scriptId;
    }

    /**
     * 计算距离自动重试还有多久。
     *
     * @return 毫秒数；非打开态返回 {@code 0}
     */
    public synchronized long retryAfterMillis() {
        if (state != State.OPEN) {
            return 0L;
        }
        long remaining = settings.cooldownMillis() - (clock.getAsLong() - openedAtMillis);
        return Math.max(0L, remaining);
    }

    /**
     * 渲染成给人看的一行状态。
     *
     * @return 文本，保证非 {@code null}
     */
    public synchronized String describe() {
        StringBuilder builder = new StringBuilder();
        if (state == State.PERMANENT) {
            return builder.append(state.displayName())
                    .append("（探测失败 ").append(openRounds).append(" 轮）").toString();
        }
        builder.append(state.displayName());
        if (state != State.CLOSED) {
            builder.append("（失败 ").append(consecutiveFailures).append(" 次");
            if (state == State.HALF_OPEN) {
                builder.append("，探测失败 ").append(openRounds).append(" 轮");
            }
            long remaining = retryAfterMillis();
            builder.append(remaining > 0L
                    ? "，约 " + ((remaining + 999L) / 1000L) + " 秒后重试"
                    : "，随时可探测");
            builder.append('）');
        }
        return builder.toString();
    }

    /**
     * 转入打开态并通知观察者。
     * <p>
     * 触发原因由调用方给出，而不是在这里现算：从正常态打开与从半开态重新打开，
     * 有价值的数字完全不同（连续失败次数 vs 第几轮探测失败），
     * 而用同一个字段凑出一句话，会让「探测了几轮」这个真正在涨的数字消失。
     *
     * @param cause 触发原因
     */
    private void openAt(String cause) {
        state = State.OPEN;
        openedAtMillis = clock.getAsLong();
        notifyOpened(cause + "，将在 " + settings.cooldownSeconds() + " 秒后自动重试");
    }

    /**
     * 通知「已打开」。
     *
     * @param detail 详情
     */
    private void notifyOpened(String detail) {
        if (listener == null) {
            return;
        }
        try {
            listener.onOpened(scriptId, state, detail);
        } catch (RuntimeException e) {
            // 记告警失败不该影响调用方的失败语义：它是旁路，不是主路径
            LOG.warn("熔断告警回调失败（打开）: script={}", scriptId, e);
        }
    }

    /**
     * 通知「已恢复」。
     *
     * @param rounds 恢复前失败了几轮探测
     */
    private void notifyRecovered(int rounds) {
        if (listener == null) {
            return;
        }
        try {
            listener.onRecovered(scriptId, rounds > 0
                    ? "探测成功，已恢复正常（此前探测失败 " + rounds + " 轮）"
                    : "调用成功，已恢复正常");
        } catch (RuntimeException e) {
            LOG.warn("熔断告警回调失败（恢复）: script={}", scriptId, e);
        }
    }
}
