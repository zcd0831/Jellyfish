package zcd.jellyfish.api.event;

/**
 * 注册选项：覆盖语义 + 顺序声明。
 * <p>
 * 默认不覆盖：命中已有注册直接失败，避免「工具被静默替换」这类难排查问题；
 * 需要覆盖时必须显式声明 {@link #override(boolean)}。
 * <p>
 * {@code order} 是唯一向插件开放的通道参数：类型级贡献按 {@code order} 升序调用处理器，
 * 默认 {@code 0}，同序按注册顺序；语义上只有插件自己知道「我的上下文比别人的更重要」。
 * 其余通道参数（谁是唯一处理器、无处理器怎么办、在内核哪个线程执行）都由插件调用的注册入口
 * 与内核的调用点决定，不在此暴露。
 *
 * @author zcd
 */
public final class RegisterOptions {

    /** 默认选项：不覆盖、顺序为 0。 */
    public static final RegisterOptions DEFAULT = new RegisterOptions(false, 0);

    /** 是否允许覆盖同键的既有注册。 */
    private final boolean override;

    /** 调用顺序，仅对类型级贡献有意义。 */
    private final int order;

    /**
     * 构造注册选项。
     *
     * @param override 是否允许覆盖
     * @param order    调用顺序
     */
    private RegisterOptions(boolean override, int order) {
        this.override = override;
        this.order = order;
    }

    /**
     * 构造覆盖选项：允许替换同一 (回调类型, 路由键) 下的既有处理器。
     *
     * @param override 是否允许覆盖
     * @return 注册选项
     */
    public static RegisterOptions override(boolean override) {
        return override ? new RegisterOptions(true, 0) : DEFAULT;
    }

    /**
     * 构造带顺序的选项。
     *
     * @param order 调用顺序
     * @return 注册选项
     */
    public static RegisterOptions order(int order) {
        return new RegisterOptions(false, order);
    }

    /**
     * 基于当前选项派生一个带指定顺序的新选项。
     *
     * @param order 调用顺序
     * @return 派生后的注册选项
     */
    public RegisterOptions withOrder(int order) {
        return new RegisterOptions(override, order);
    }

    /**
     * 判断是否允许覆盖。
     *
     * @return 允许覆盖返回 {@code true}
     */
    public boolean isOverride() {
        return override;
    }

    /**
     * 获取调用顺序。
     *
     * @return 调用顺序
     */
    public int getOrder() {
        return order;
    }

    @Override
    public String toString() {
        return "RegisterOptions{override=" + override + ", order=" + order + '}';
    }
}
