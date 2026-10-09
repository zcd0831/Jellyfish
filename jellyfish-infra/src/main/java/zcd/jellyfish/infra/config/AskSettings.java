package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code jellyfish.json} 的 {@code ask} 段：向用户提问（{@code ask_user} 工具）的运行期参数。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾）。当前只有一项：提问等待超时。
 * <p>
 * <b>为什么不复用 {@code permission.approvalTimeoutSeconds}</b>：审批与提问是两件事——前者是
 * 权限判定卡在半路等人放行（等不到就按拒绝处理），后者是模型在向你确认信息（等不到就照自己的
 * 判断往下走）。两者的合理等待长度并不相同，共用一个键会让「我想让审批等久点」顺带改掉提问的行为。
 * 配置段的边界应当与语义的边界一致。
 * <p>
 * <b>{@code 0} 表示「永不超时」</b>：提问请求发生在 {@code react} 线程上并被同步等待（见
 * {@code AskChannel}），因此配 {@code 0} 就是让那条线程一直等到有人作答。留这个口子是因为
 * 「人离开一会儿再回来」是真实场景；代价是那条线程在作答之前不会自愈，而 {@code react} 池只有 8 条
 * 线程（= 并发顶层回合的上限）。因此它是一种<b>显式、要明知代价</b>的选择：配了会在启动时发一条
 * {@link #warnings() 告警}（见 {@code ConfigWarningEvent}，来源 {@code ask}），否则「故意配的」
 * 与「手滑写了个 0」在日志里长得一样。
 * <p>
 * <b>负数按非法处理</b>：回退缺省 {@value #DEFAULT_TIMEOUT_SECONDS} 秒并记进 {@link #warnings()}，
 * 与其余数值项同口径——配置问题不阻断启动。缺省 {@value #DEFAULT_TIMEOUT_SECONDS} 秒与审批同量级：
 * 够人看清问题并作答，挂住了也能自愈。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class AskSettings {

    /** 提问等待超时缺省秒数。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** 「永不超时」的配置值：等到有人作答为止，不再超时。 */
    public static final int INFINITE_TIMEOUT_SECONDS = 0;

    /** 提问等待超时秒数；{@link #INFINITE_TIMEOUT_SECONDS} 表示永不超时。 */
    private final int timeoutSeconds;

    /**
     * 配置里实际写下的值；未配置时为 {@code null}。
     * <p>
     * <b>为什么把它留下</b>：构造器把非法值静默换成缺省值（见类注释），换完之后「用户写过什么」
     * 就再也看不出来了。留下原始值，才能把「写错了」这件事如实报出来（见 {@link #warnings()}）。
     */
    private final Integer configuredTimeoutSeconds;

    /**
     * 构造缺省提问设置。
     */
    public AskSettings() {
        this(null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param timeoutSeconds 提问等待超时秒数；{@code 0} 表示永不超时，{@code null} 或负数按缺省值处理
     */
    @JsonCreator
    public AskSettings(@JsonProperty("timeoutSeconds") Integer timeoutSeconds) {
        this.configuredTimeoutSeconds = timeoutSeconds;
        this.timeoutSeconds = timeoutSeconds == null || timeoutSeconds < 0
                ? DEFAULT_TIMEOUT_SECONDS
                : timeoutSeconds;
    }

    /**
     * 获取提问等待超时秒数。
     * <p>
     * <b>返回 {@code 0} 不是「立刻超时」，而是「永远等下去」</b>：调用方应当先看 {@link #isInfinite()}，
     * 否则 {@code 0} 会被当成一个只有 0 秒的等待。
     *
     * @return 超时秒数，非负；{@code 0} 表示永不超时
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 判断是否配置为「永不超时」。
     *
     * @return 等待不设上限时返回 {@code true}
     */
    public boolean isInfinite() {
        return timeoutSeconds == INFINITE_TIMEOUT_SECONDS;
    }

    /**
     * 汇报「配置里写的值需要提醒一声」这件事，没有时返回空列表。
     * <p>
     * 两类各报一条：<b>负数</b>是写错了（回退缺省，如实报出写了什么、按什么生效）；<b>{@code 0}</b>
     * 是刻意放开（不报就与写错无法区分，而它占住的是 react 线程）。只报事实，不替用户改判。
     *
     * @return 告警文本列表，没有问题时为空
     */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<String>();
        if (configuredTimeoutSeconds != null && configuredTimeoutSeconds < 0) {
            warnings.add("timeoutSeconds=" + configuredTimeoutSeconds
                    + " 不是合法值（必须 ≥0；0 表示永不超时），已按缺省 "
                    + DEFAULT_TIMEOUT_SECONDS + " 秒生效");
        } else if (isInfinite()) {
            warnings.add("timeoutSeconds=0：提问将永不超时，一直等到有人作答。提问在 react 线程上同步等待，"
                    + "而那个池只有 8 条线程（= 并发顶层回合上限），每一次这样的等待都占住其中一条；"
                    + "无人作答时那个回合不会自愈（外壳退出、回合被取消仍会放开）");
        }
        return warnings;
    }

    /**
     * 判断是否与缺省值完全一致。
     * <p>
     * 供 {@link JellyfishSettings#isEmpty()} 判断「整份运行期设置是否什么都没配」，
     * 因此只比较是否等于缺省，不比较字段来源；「永不超时」不等于缺省。
     *
     * @return 等于缺省值返回 {@code true}
     */
    public boolean isDefault() {
        return timeoutSeconds == DEFAULT_TIMEOUT_SECONDS;
    }
}
