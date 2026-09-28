package zcd.jellyfish.api.extension;

/**
 * 输入指令名片：注册输入指令处理器时与处理器一起落表的「一句说明」。
 * <p>
 * <b>不含标记字符</b>：标记就是注册时的路由键（{@code PluginContext.handle(InputDirectiveRequest.class, "!", ...)}
 * 的第一个参数），因此不存在「名片上的标记与路由键不一致」这本错账——外壳取清单时从注册项取标记、
 * 从名片取说明，两者天然对齐。
 * <p>
 * <b>标记就是路由键，因此独占性由注册表保证</b>：两个插件都想要 {@code !} 时，第二个会在插件启动时
 * 以 {@code DUPLICATE_HANDLER} 当场暴露，而不是静默抢跑。
 * <p>
 * 与处理器一起落表的好处：标记清单不需要第二份目录，插件下架时名片随注册一起消失。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputDirectiveDescriptor {

    /** 一句话用途说明。 */
    private final String summary;

    /**
     * 构造输入指令名片。
     *
     * @param summary 一句话说明，可为 {@code null}
     */
    public InputDirectiveDescriptor(String summary) {
        this.summary = summary;
    }

    /**
     * 获取一句话说明。
     *
     * @return 一句话说明，未提供时为 {@code null}
     */
    public String getSummary() {
        return summary;
    }

    @Override
    public String toString() {
        return "InputDirectiveDescriptor{summary=" + summary + '}';
    }
}
