package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code jellyfish.json} 的 {@code permission} 段：权限判定里「需要人工审批」分支的运行期参数。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾）。当前只有一项：审批等待超时。
 * 它必须有缺省值而不能是「不超时」——审批请求发生在 {@code react} 线程上，且被
 * {@code ReActLooper} 同步等待（见 {@code PermissionManager#resolveApproval}），
 * 一个永远不来的答复就是一条永远不返回的线程；缺省 120 秒是「够人看清一次工具调用」与
 * 「挂了也能自愈」之间的折中。
 * <p>
 * <b>非正数一律回退缺省值而不是报错</b>，与 {@link ReactSettings} 同口径：配置问题不阻断启动，
 * 而且「超时为 0」在语义上只会退化成「一律拒绝」，与「审批者缺席」撞成同一种表现，不如直接当没配。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class PermissionApprovalSettings {

    /** 审批等待超时缺省秒数。 */
    public static final int DEFAULT_APPROVAL_TIMEOUT_SECONDS = 120;

    /**
     * 超过这个秒数就告警（但不拒绝）。
     * <p>
     * 审批请求是在 {@code react} 线程上同步等待的，写下一个远超「人看清一次工具调用」所需的
     * 秒数（一个笔误的 {@code 99999}）会让那条线程挂很久，而原先这件事完全无声。
     */
    public static final int WARN_ABOVE_TIMEOUT_SECONDS = 3600;

    /** 审批等待超时秒数。 */
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
     * @param approvalTimeoutSeconds 审批等待超时秒数，非正数或缺省按缺省值处理
     */
    @JsonCreator
    public PermissionApprovalSettings(@JsonProperty("approvalTimeoutSeconds") Integer approvalTimeoutSeconds) {
        this.configuredTimeoutSeconds = approvalTimeoutSeconds;
        this.approvalTimeoutSeconds = approvalTimeoutSeconds != null && approvalTimeoutSeconds > 0
                ? approvalTimeoutSeconds : DEFAULT_APPROVAL_TIMEOUT_SECONDS;
    }

    /**
     * 取审批等待超时秒数。
     *
     * @return 超时秒数，保证为正
     */
    public int getApprovalTimeoutSeconds() {
        return approvalTimeoutSeconds;
    }

    /**
     * 汇报「配置里写的值被改过」这件事，没有时返回空列表。
     * <p>
     * 只报事实（写了什么、生效的是什么、为什么），不替用户改判——回退本身是本仓库的既定口径
     * （配置问题不阻断启动），这里补的只是「让它可见」。
     *
     * @return 告警文本列表，没有问题时为空
     */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<String>();
        if (configuredTimeoutSeconds != null && configuredTimeoutSeconds <= 0) {
            warnings.add("approvalTimeoutSeconds=" + configuredTimeoutSeconds
                    + " 不是合法值（必须为正），已按缺省 " + DEFAULT_APPROVAL_TIMEOUT_SECONDS + " 秒生效。"
                    + "注意这不是「立即拒绝」：审批闸门会等满 " + DEFAULT_APPROVAL_TIMEOUT_SECONDS
                    + " 秒，期间人工批准仍然生效");
        } else if (approvalTimeoutSeconds > WARN_ABOVE_TIMEOUT_SECONDS) {
            warnings.add("approvalTimeoutSeconds=" + approvalTimeoutSeconds + " 超过 "
                    + WARN_ABOVE_TIMEOUT_SECONDS + " 秒：审批请求在 react 线程上同步等待，"
                    + "这么大的值会让它挂很久（写错了？）");
        }
        return warnings;
    }

    /**
     * 判断是否与缺省值完全一致。
     * <p>
     * 供 {@link JellyfishSettings#isEmpty()} 判断「整份运行期设置是否什么都没配」，
     * 因此只比较是否等于缺省，不比较字段来源。
     *
     * @return 等于缺省值返回 {@code true}
     */
    public boolean isDefault() {
        return approvalTimeoutSeconds == DEFAULT_APPROVAL_TIMEOUT_SECONDS;
    }
}
