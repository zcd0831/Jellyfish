package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /** 审批等待超时秒数。 */
    private final int approvalTimeoutSeconds;

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
        this.approvalTimeoutSeconds = approvalTimeoutSeconds != null && approvalTimeoutSeconds > 0
                ? approvalTimeoutSeconds : DEFAULT_APPROVAL_TIMEOUT_SECONDS;
    }

    /**
     * 获取审批等待超时秒数。
     *
     * @return 超时秒数，保证为正
     */
    public int getApprovalTimeoutSeconds() {
        return approvalTimeoutSeconds;
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
