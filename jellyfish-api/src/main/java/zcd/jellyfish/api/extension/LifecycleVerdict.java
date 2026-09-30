package zcd.jellyfish.api.extension;

/**
 * 生命周期裁定：插件对「内核即将做一件不可逆的事」的回答。
 * <p>
 * 三个可取消的生命周期钩子（压缩前、会话关闭前、回合开始前）共用它，避免三份形状相同、
 * 语义略有差异的类型。它只有两态：放行，或者带着一句理由拦下。
 * <p>
 * <b>为什么没有第三态</b>：这些钩子回答的是「要不要做」。想做些别的（改保留条数、换输入文本）
 * 是各个钩子自己的结果类型的事（{@link CompactionDirective} / {@link TurnDirective}），
 * 与「放不放行」正交，混进同一个类型会让每个钩子都背上不属于它的字段。
 * <p>
 * <b>否决不是安全边界</b>：这些钩子让插件拦下内核动作，但拦下本身不产生权限结论——
 * 该拒的调用照样要经过权限判定与审批。它们的价值在「给用户一个正式的拦截点」，
 * 而不是「让插件替内核做安全判断」。
 * <p>
 * <b>否决只在调用点声明的场合生效</b>：每个钩子自己说清楚哪一档否决会被忽略
 * （例如进程收尾时不允许被插件拖住）。插件不要假设 {@code cancel} 一定生效。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class LifecycleVerdict {

    /** 单例：放行。 */
    private static final LifecycleVerdict PROCEED = new LifecycleVerdict(false, null);

    /** 是否拦下。 */
    private final boolean cancelled;

    /** 拦下的理由，可为 {@code null}。 */
    private final String reason;

    /**
     * 构造裁定。
     *
     * @param cancelled 是否拦下
     * @param reason    拦下的理由，可为 {@code null}
     */
    public LifecycleVerdict(boolean cancelled, String reason) {
        this.cancelled = cancelled;
        this.reason = reason;
    }

    /**
     * 构造「放行」裁定。
     *
     * @return 放行裁定
     */
    public static LifecycleVerdict proceed() {
        return PROCEED;
    }

    /**
     * 构造「拦下」裁定。
     *
     * @param reason 拦下的理由，可为 {@code null}
     * @return 拦下裁定
     */
    public static LifecycleVerdict cancel(String reason) {
        return new LifecycleVerdict(true, reason);
    }

    /**
     * 判断是否拦下。
     *
     * @return 拦下返回 {@code true}
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 获取拦下的理由。
     *
     * @return 理由，放行时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "LifecycleVerdict{cancelled=" + cancelled + ", reason=" + reason + '}';
    }
}
