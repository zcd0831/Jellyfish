package zcd.jellyfish.api.event;

/**
 * 注册选项：目前只包含覆盖语义。
 * <p>
 * 默认不覆盖：命中已有注册直接失败，避免「工具被静默替换」这类难排查问题；
 * 需要覆盖时必须显式声明 {@link #override(boolean)}。
 *
 * @author zcd
 */
public final class RegisterOptions {

    /** 默认选项：不覆盖，命中已有注册直接失败。 */
    public static final RegisterOptions DEFAULT = new RegisterOptions(false);

    /** 是否允许覆盖同键的既有注册。 */
    private final boolean override;

    /**
     * 构造注册选项。
     *
     * @param override 是否允许覆盖
     */
    private RegisterOptions(boolean override) {
        this.override = override;
    }

    /**
     * 构造覆盖选项：允许替换同一 (命令类型, 路由键) 下的既有处理器。
     *
     * @param override 是否允许覆盖
     * @return 注册选项
     */
    public static RegisterOptions override(boolean override) {
        return override ? new RegisterOptions(true) : DEFAULT;
    }

    /**
     * 判断是否允许覆盖。
     *
     * @return 允许覆盖返回 {@code true}
     */
    public boolean isOverride() {
        return override;
    }

    @Override
    public String toString() {
        return "RegisterOptions{override=" + override + '}';
    }
}
