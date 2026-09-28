package zcd.jellyfish.api.extension;

/**
 * 行内引用名片：注册引用补全处理器时与处理器一起落表的「一句说明」。
 * <p>
 * <b>不含标记字符</b>：标记就是注册时的路由键（{@code PluginContext.handle(InputReferenceRequest.class, "@", ...)}
 * 的第一个参数），与 {@link InputDirectiveDescriptor} 同口径。
 * <p>
 * <b>与输入指令名片分成两个类型</b>：两者的调用时机完全不同——引用补全在用户每敲一个字符时被问
 * （必须快、纯只读），输入指令在用户提交时才被问（一次解析）。合成一个描述符会让
 * 「这条标记要不要参与每帧补全」变成一个需要判别的字段，而它本来就是由扩展点类型决定的。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputReferenceDescriptor {

    /** 一句话用途说明。 */
    private final String summary;

    /**
     * 构造行内引用名片。
     *
     * @param summary 一句话说明，可为 {@code null}
     */
    public InputReferenceDescriptor(String summary) {
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
        return "InputReferenceDescriptor{summary=" + summary + '}';
    }
}
