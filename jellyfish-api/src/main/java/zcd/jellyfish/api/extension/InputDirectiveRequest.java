package zcd.jellyfish.api.extension;

/**
 * 输入指令请求：外壳在用户提交一行输入时构造，询问「这行输入有没有插件要接管」。
 * <p>
 * <b>这是「类型 + 路由键」的扩展点，路由键就是标记字符</b>：外壳取输入去掉首尾空白后的第一个字符，
 * 用它做精确查找。于是「两个插件都想管 {@code !}」在注册期就撞「同键唯一」，
 * 而不是在运行期变成一个取决于注册顺序的静默竞争。
 * <p>
 * <b>没有插件的标记不存在</b>：注册表里查不到就返回空，外壳按普通文本处理——
 * 这正是「`!` 是 shell 插件的功能，卸了它就没有 `!`」这条口径的落点。
 * <p>
 * <b>一次 {@code handle} 调用必须是一次纯解析</b>：它在渲染线程上同步执行（外壳提交时），
 * 不得起进程、不得写盘、不得阻塞。真正的执行由内核按 {@link InputDirectiveResult} 的声明去做。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputDirectiveRequest extends ExtensionRequest<InputDirectiveResult> {

    /** 标记字符，也是路由键。 */
    private final String marker;

    /** 用户输入的原文（未修剪），处理器自行决定怎么解析。 */
    private final String input;

    /**
     * 构造输入指令请求。
     *
     * @param marker    标记字符，不可为空白、长度必须为 1
     * @param input     用户输入原文，可为 {@code null}（等价空串）
     * @param sessionId 会话标识，可为 {@code null}
     */
    public InputDirectiveRequest(String marker, String input, String sessionId) {
        super(InputDirectiveResult.class, sessionId);
        this.marker = InputMarkers.requireMarker(marker);
        this.input = input == null ? "" : input;
    }

    @Override
    public String getRouteKey() {
        return marker;
    }

    /**
     * 获取标记字符。
     *
     * @return 标记字符，保证非 {@code null}
     */
    public String getMarker() {
        return marker;
    }

    /**
     * 获取用户输入原文。
     *
     * @return 输入原文（未修剪），保证非 {@code null}
     */
    public String getInput() {
        return input;
    }

}
