package zcd.jellyfish.api.extension;

/**
 * 行内引用请求：外壳在输入框里发现「光标正处在一个已注册标记的片段里」时构造，询问可选值。
 * <p>
 * <b>这是「类型 + 路由键」的扩展点，路由键就是标记字符</b>（如 {@code @}）。
 * <p>
 * <b>片段切分由内核统一完成，插件不重复实现</b>：外壳从光标向前扫到空白或行首，
 * 得到一个以标记开头的片段，把标记之后的原文作为 {@link #getToken()} 交下来。
 * 于是「什么算一个引用片段」只有一份规则，插件只回答「这个片段下有哪些候选」。
 * <p>
 * <b>调用时机是渲染线程、每帧可能一次</b>：处理器必须纯只读、必须快、不得发布事件、不得阻塞
 * （与界面贡献处理器同一档约束）。慢的实现在这里会直接卡住整个界面。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputReferenceRequest extends ExtensionRequest<InputReferenceResult> {

    /** 标记字符，也是路由键。 */
    private final String marker;

    /** 标记之后已输入的片段（不含标记本身），不含空白，可为空串。 */
    private final String token;

    /** 输入框全文，供需要更多上下文的实现参考。 */
    private final String input;

    /** 光标在 {@link #getInput()} 中的字符偏移。 */
    private final int cursor;

    /**
     * 构造行内引用请求。
     *
     * @param marker    标记字符，不可为空白、长度必须为 1
     * @param token     标记之后的片段，可为 {@code null}（等价空串）
     * @param input     输入框全文，可为 {@code null}（等价空串）
     * @param cursor    光标字符偏移，负数按 0 处理
     * @param sessionId 会话标识，可为 {@code null}
     */
    public InputReferenceRequest(String marker, String token, String input, int cursor, String sessionId) {
        super(InputReferenceResult.class, sessionId);
        this.marker = InputMarkers.requireMarker(marker);
        this.token = token == null ? "" : token;
        this.input = input == null ? "" : input;
        this.cursor = Math.max(0, cursor);
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
     * 获取标记之后已输入的片段。
     *
     * @return 片段，保证非 {@code null}（可能是空串）
     */
    public String getToken() {
        return token;
    }

    /**
     * 获取输入框全文。
     *
     * @return 输入框全文，保证非 {@code null}
     */
    public String getInput() {
        return input;
    }

    /**
     * 获取光标字符偏移。
     *
     * @return 光标偏移，保证非负
     */
    public int getCursor() {
        return cursor;
    }
}
