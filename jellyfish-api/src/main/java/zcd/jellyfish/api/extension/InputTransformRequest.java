package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 输入改写请求：内核在用户按下回车之后、把这段文本送去解析之前询问「要不要改写它」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：每次提交都问一遍，
 * 不按会话或外壳分槽。需要按外壳分流时，处理器自己看 {@link #getSource()}。
 * <p>
 * <b>它在管道里的位置是硬的</b>（三个约束，缺一个就会出问题）：
 * <ol>
 *     <li><b>排在命令判定之后</b>——若排在之前，一个插件就能把 {@code /help} 改写成别的东西，
 *     用户看到的与执行的不是同一件事。命令域是用户最显式的意图，优先级最高，改动不了它；</li>
 *     <li><b>排在指令解析之前，且指令按改写后的文本解析</b>——这样「把 {@code !ls} 归一化成
 *     {@code ! ls}」这类需求才成立；</li>
 *     <li><b>排在建会话之前</b>——首页（还没有会话）也要能拦下输入，否则「不建会话」那条约定
 *     在首页上不成立。</li>
 * </ol>
 * <p>
 * <b>后果之一是插件理论上能把普通文本改成 {@code !命令}，从而触发一次工具执行</b>。这个代价是刻意
 * 接受的：插件本来就是同进程的任意代码，而工具执行仍走完整的权限与审批链路，它拿到的是一次正常的、
 * 要过权限的调用，不是绕过。请<b>不要</b>把这一点当成漏洞来「修」——修掉它等于把「归一化」这类真实
 * 需求一起禁掉。
 * <p>
 * <b>必须快且不得阻塞</b>：TUI 路径上它在<b>渲染线程</b>上同步执行（与行内引用补全同一个线程）。
 * 因此 handler 只能做纯计算，不得回调内核、不得发布事件。内核不为它设超时，这是同步侧既有语义。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputTransformRequest extends ExtensionRequest<InputTransformResult> {

    /** 输入来自哪种外壳。 */
    public enum Source {
        /** 单次调用、不交互的命令行外壳。 */
        CLI,
        /** 交互式终端界面外壳。 */
        TUI,
        /** HTTP 服务外壳。 */
        SERVER
    }

    /** 用户输入原文（链式传递时是上一个处理器产出的文本）。 */
    private final String text;

    /** 输入来自哪种外壳。 */
    private final Source source;

    /** 当前是否已有会话。 */
    private final boolean hasSession;

    /**
     * 构造输入改写请求。
     * <p>
     * {@code sessionId} 允许为 {@code null}：TUI 的首页上还没有会话，那正是最需要拦下输入的时候。
     * 基类把它原样带出来，处理器不必自己判空。
     *
     * @param sessionId  当前会话标识，可为 {@code null}（首页无会话）
     * @param text       用户输入文本，可为 {@code null}
     * @param source     输入来自哪种外壳，可为 {@code null}（按 {@link Source#CLI} 处理）
     * @param hasSession 当前是否已有会话
     * @throws JellyfishException 文本为 {@code null} 时抛出
     */
    public InputTransformRequest(String sessionId, String text, Source source, boolean hasSession) {
        super(InputTransformResult.class, sessionId);
        if (text == null) {
            throw new JellyfishException("input text must not be null");
        }
        this.text = text;
        this.source = source == null ? Source.CLI : source;
        this.hasSession = hasSession;
    }

    @Override
    public String getRouteKey() {
        return null;
    }

    /**
     * 获取用户输入文本。
     * <p>
     * 链式传递时它是<b>上一个处理器产出的</b>值（首个处理器拿到的是原文）。
     *
     * @return 输入文本，保证非 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取输入来自哪种外壳。
     *
     * @return 外壳种类，保证非 {@code null}
     */
    public Source getSource() {
        return source;
    }

    /**
     * 判断当前是否已有会话。
     * <p>
     * 为假表示这是首页上的第一条输入。它与「{@link #getSessionId()} 是否为空」是同一件事的两种读法，
     * 后者更直接；保留本方法是因为多数处理器只关心「有没有会话」。
     *
     * @return 已有会话返回 {@code true}
     */
    public boolean hasSession() {
        return hasSession;
    }
}
