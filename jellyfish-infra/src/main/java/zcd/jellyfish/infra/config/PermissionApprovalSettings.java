package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code jellyfish.json} 的 {@code permission} 段：权限判定里「需要人工审批」分支的运行期参数。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾）。当前只有一项：审批等待超时。
 * <p>
 * <b>{@code 0} 表示「永不超时」</b>：审批请求发生在 {@code react} 线程上，且被
 * {@code ReActLooper} 同步等待（见 {@code PermissionManager#resolveApproval}），因此配 {@code 0}
 * 就是让那条线程一直等到有人裁决。它与提问那侧（{@code AskSettings}）同口径。
 * <p>
 * <b>这一侧的代价比提问更重，配之前值得看清</b>：审批是 fail-closed——策略说了「这个工具要人看一眼」，
 * 等不到人本应按拒绝处理。永不超时把这条语义换成「等不到人就一直卡住」，于是外部表现从「工具调用失败」
 * 变成「界面再没反应」；被占住的那条 {@code react} 线程在有人裁决之前不会自愈（外壳退出、回合被取消
 * 仍会放开），而池子只有 8 条线程。因此配了会在启动时发一条 {@link #warnings() 告警}
 * （见 {@code ConfigWarningEvent}，来源 {@code permission}）。
 * <p>
 * <b>负数按非法处理</b>：回退缺省 {@value #DEFAULT_APPROVAL_TIMEOUT_SECONDS} 秒并记进
 * {@link #warnings()}——配置问题不阻断启动。缺省 {@value #DEFAULT_APPROVAL_TIMEOUT_SECONDS} 秒是
 * 「够人看清一次工具调用」与「挂了也能自愈」之间的折中。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class PermissionApprovalSettings {

    /** 审批等待超时缺省秒数。 */
    public static final int DEFAULT_APPROVAL_TIMEOUT_SECONDS = 120;

    /** 「永不超时」的配置值：等到有人裁决为止，不再超时。 */
    public static final int INFINITE_TIMEOUT_SECONDS = 0;

    /**
     * 超过这个秒数就告警（但不拒绝）。
     * <p>
     * 审批请求是在 {@code react} 线程上同步等待的，写下一个远超「人看清一次工具调用」所需的
     * 秒数（一个笔误的 {@code 99999}）会让那条线程挂很久，而原先这件事完全无声。
     */
    public static final int WARN_ABOVE_TIMEOUT_SECONDS = 3600;

    /** 审批等待超时秒数；{@link #INFINITE_TIMEOUT_SECONDS} 表示永不超时。 */
    private final int approvalTimeoutSeconds;

    /**
     * 配置里实际写下的值；未配置时为 {@code null}。
     * <p>
     * <b>为什么把它留下</b>：构造器把非法值静默换成缺省值（见类注释），换完之后「用户写过什么」
     * 就再也看不出来了。留下原始值，才能把「写错了」这件事如实报出来（见 {@link #warnings()}）。
     */
    private final Integer configuredTimeoutSeconds;

    /**
     * 构造缺省审批设置。
     */
    public PermissionApprovalSettings() {
        this(null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param approvalTimeoutSeconds 审批等待超时秒数；{@code 0} 表示永不超时，{@code null} 或负数按缺省值处理
     */
    @JsonCreator
    public PermissionApprovalSettings(@JsonProperty("approvalTimeoutSeconds") Integer approvalTimeoutSeconds) {
        this.configuredTimeoutSeconds = approvalTimeoutSeconds;
        this.approvalTimeoutSeconds = approvalTimeoutSeconds == null || approvalTimeoutSeconds < 0
                ? DEFAULT_APPROVAL_TIMEOUT_SECONDS
                : approvalTimeoutSeconds;
    }

    /**
     * 取审批等待超时秒数。
     * <p>
     * <b>返回 {@code 0} 不是「立即拒绝」，而是「永远等下去」</b>：调用方应当先看 {@link #isInfinite()}，
     * 否则 {@code 0} 会被当成一个只有 0 秒的等待。
     *
     * @return 超时秒数，非负；{@code 0} 表示永不超时
     */
    public int getApprovalTimeoutSeconds() {
        return approvalTimeoutSeconds;
    }

    /**
     * 判断是否配置为「永不超时」。
     *
     * @return 等待不设上限时返回 {@code true}
     */
    public boolean isInfinite() {
        return approvalTimeoutSeconds == INFINITE_TIMEOUT_SECONDS;
    }

    /**
     * 汇报「配置里写的值需要提醒一声」这件事，没有时返回空列表。
     * <p>
     * 三类各报一条：<b>负数</b>是写错了（回退缺省，如实报出写了什么、按什么生效）；<b>{@code 0}</b>
     * 是刻意放开，但把 fail-closed 改成了「等不到人就一直卡住」，必须让人看见；<b>超过
     * {@value #WARN_ABOVE_TIMEOUT_SECONDS} 秒</b>的值同样是挂住线程的隐患（多半是笔误）。
     * 只报事实（写了什么、生效的是什么、为什么），不替用户改判。
     *
     * @return 告警文本列表，没有问题时为空
     */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<String>();
        if (configuredTimeoutSeconds != null && configuredTimeoutSeconds < 0) {
            warnings.add("approvalTimeoutSeconds=" + configuredTimeoutSeconds
                    + " 不是合法值（必须 ≥0；0 表示永不超时），已按缺省 "
                    + DEFAULT_APPROVAL_TIMEOUT_SECONDS + " 秒生效。"
                    + "注意这不是「立即拒绝」：审批闸门会等满 " + DEFAULT_APPROVAL_TIMEOUT_SECONDS
                    + " 秒，期间人工批准仍然生效");
        } else if (isInfinite()) {
            warnings.add("approvalTimeoutSeconds=0：审批将永不超时，一直等到有人裁决。审批是 fail-closed"
                    + "（等不到人本应按拒绝处理），这等于把它改成「等不到人就一直卡住」："
                    + "那条 react 线程在有人裁决之前不会自愈（外壳退出、回合被取消仍会放开），"
                    + "而池子只有 8 条线程（= 并发顶层回合上限）");
        } else if (approvalTimeoutSeconds > WARN_ABOVE_TIMEOUT_SECONDS) {
            warnings.add("approvalTimeoutSeconds=" + approvalTimeoutSeconds + " 超过 "
                    + WARN_ABOVE_TIMEOUT_SECONDS + " 秒：审批请求在 react 线程上同步等待，"
                    + "这么大的值会让它挂很久（写错了？）；真要「永不超时」应当写 0");
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
        return approvalTimeoutSeconds == DEFAULT_APPROVAL_TIMEOUT_SECONDS;
    }
}
