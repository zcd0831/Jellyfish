package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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
 * <b>它必须有超时</b>：提问请求发生在 {@code react} 线程上并被同步等待（见
 * {@code AskChannel}），一个永远不来的答复就是一条永远不返回的线程——而 {@code react} 池
 * 只有 8 条线程，也就是并发顶层回合的上限。缺省 120 秒与审批同量级：够人看清问题并作答，
 * 挂住了也能自愈。
 * <p>
 * <b>非正数一律回退缺省值而不是报错</b>，与 {@link PermissionApprovalSettings} 同口径：
 * 配置问题不阻断启动，而且「超时为 0」在语义上只会退化成「一律超时」，不如直接当没配。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class AskSettings {

    /** 提问等待超时缺省秒数。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** 提问等待超时秒数。 */
    private final int timeoutSeconds;

    /**
     * 构造缺省提问设置。
     */
    public AskSettings() {
        this(null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param timeoutSeconds 提问等待超时秒数，非正数或缺省按缺省值处理
     */
    @JsonCreator
    public AskSettings(@JsonProperty("timeoutSeconds") Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds != null && timeoutSeconds > 0
                ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * 获取提问等待超时秒数。
     *
     * @return 超时秒数，保证为正
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
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
        return timeoutSeconds == DEFAULT_TIMEOUT_SECONDS;
    }
}
