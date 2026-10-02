package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.ui.UiLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一条外壳贡献：插件想推给「当前这一个外壳」的可渲染内容或失效提示。
 * <p>
 * <b>它解决什么问题</b>：在那之前，插件没有<b>任何准实时</b>的推送路径——发个
 * {@code PluginNotificationEvent} 没人看（外壳不订阅事件通道），改会话扩展条目要等外壳下一帧来拉。
 * 贡献是「插件往外发」的第三条边：{@code emit} 是广播给订阅者，{@code submit} 是往里投动作，
 * {@code present} 是把一份载荷交给活着的那一个外壳。
 * <p>
 * <b>它是展示数据，不是请求</b>。{@link Kind} 是一份<b>封闭</b>清单，两种取值都不携带
 * 「发给谁」「让模型看见什么」这类载荷，因此插件<b>在类型上就</b>不可能凭贡献新开会话或新起回合：
 * <ul>
 *     <li>{@link #notice} 只是「显示这些行」，不落盘、不进模型上下文、不改会话；</li>
 *     <li>{@link #invalidated} 只是「我贡献的内容脏了，请重新拉取」，真源仍是拉取式的那份。</li>
 * </ul>
 * 插件要让模型看见东西，唯一通路仍是
 * {@code PluginContext.submit(PluginAction.sendUserMessage(...))}，而它照旧要求存在在途顶层回合。
 * <p>
 * <b>可丢</b>：贡献走尽力 lane（每 owner 有界队列、同 key 可合并、满了丢最新）。
 * 因此 {@code present} 的返回值里会出现「没赶上」，而那不是错误——见
 * {@link ShellContributionStatus}。
 * <p>
 * <b>只入队，不阻塞调用方</b>：{@code present} 可能在插件自己的线程上调用，
 * 也可能在插件的工具回调里调用，任何阻塞都会顺着那条线传下去。
 * <p>
 * <b>渲染归外壳</b>：请遵守 {@link UiLine} / {@code UiSegment} 的纪律，
 * 不要用 ANSI 转义序列表达强调——转义序列不占显示列，会让外壳没法按宽度正确折行。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ShellContribution {

    /**
     * 作用域：这条贡献属于会话，还是属于外壳本身。
     */
    public enum Scope {

        /**
         * 会话级：跟着某个会话走，外壳只在「当前会话是它」时渲染。
         * <p>
         * <b>它仍然不进消息序列</b>：外壳把它插进消息流的位置（TUI 的
         * {@code ShellNotice} 语义），但它不是会话消息列表的一员，不落盘、不发给模型。
         */
        SESSION,

        /** 外壳级：与任何会话无关，外壳的通知区直接渲染。 */
        SHELL
    }

    /**
     * 贡献种类：一份封闭清单。
     * <p>
     * <b>插件不能扩展它</b>——取值由静态工厂决定，没有公开的重载入口。
     */
    public enum Kind {

        /** 一条通知：{@link #getLines()} 是要显示的内容。 */
        NOTICE,

        /** 一条失效提示：「我贡献的内容脏了」。没有内容，只有 {@link #getWhat()} 这个定位线索。 */
        INVALIDATED
    }

    /**
     * 严重程度：映射到外壳的中性 / 警示 / 错误样式。
     * <p>
     * 与 {@code UiEmphasis} 同一口径——这是<b>语义</b>，不是颜色；外壳决定怎么画。
     */
    public enum Severity {

        /** 中性信息。 */
        INFO,

        /** 需要留意但不致命。 */
        WARN,

        /** 失败。 */
        ERROR
    }

    /** 作用域。 */
    private final Scope scope;

    /** 贡献种类。 */
    private final Kind kind;

    /** 会话标识；{@link Scope#SHELL} 时为 {@code null}。 */
    private final String sessionId;

    /** 合并键：非空时「同 owner + 同 key 的后到者覆盖先到者」，{@code null} 表示不做合并。 */
    private final String key;

    /** 严重程度。 */
    private final Severity severity;

    /** 内容行，保证非 {@code null}；{@link Kind#INVALIDATED} 时为空列表。 */
    private final List<UiLine> lines;

    /** 失效定位线索，{@link Kind#NOTICE} 时为 {@code null}。 */
    private final String what;

    /**
     * 构造贡献。
     * <p>
     * <b>它保持私有</b>：种类与作用域的合法组合由静态工厂钉住，插件拿不到「造一个第四种 kind」的入口。
     *
     * @param scope     作用域，不可为 {@code null}
     * @param kind      贡献种类，不可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     * @param key       合并键，可为 {@code null}
     * @param severity  严重程度，可为 {@code null}（按 {@link Severity#INFO} 处理）
     * @param lines     内容行，可为 {@code null} 或空
     * @param what      失效定位线索，可为 {@code null}
     */
    private ShellContribution(Scope scope, Kind kind, String sessionId, String key, Severity severity,
                              List<UiLine> lines, String what) {
        if (scope == null) {
            throw new JellyfishException("shell contribution scope must not be null");
        }
        this.scope = scope;
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.sessionId = sessionId;
        this.key = key;
        this.severity = severity == null ? Severity.INFO : severity;
        this.lines = lines == null || lines.isEmpty()
                ? Collections.<UiLine>emptyList()
                : Collections.unmodifiableList(new ArrayList<UiLine>(lines));
        this.what = what;
    }

    /**
     * 构造一条通知。
     * <p>
     * <b>空 {@code lines} 是「不显示」，不是「清空」</b>：要收回已经显示出来的通知，
     * 请让外壳的时间淘汰或显示上限把它们带走，而不是发一条空通知——那会让「什么都不显示」
     * 变成一个有副作用的动作，而外壳无法区分「插件发空了」与「插件不确定」。
     *
     * @param scope     作用域，不可为 {@code null}
     * @param sessionId 目标会话标识；{@link Scope#SESSION} 时必填且必须已存在
     * @param key       合并键；非空时同 owner + 同 key 的后到者覆盖先到者（进度类通知原地更新就用它）
     * @param severity  严重程度，可为 {@code null}（按 {@link Severity#INFO} 处理）
     * @param lines     内容行，可为 {@code null} 或空（等价于不显示）
     * @return 通知贡献，保证非 {@code null}
     * @throws JellyfishException {@code scope} 为 {@code null} 时抛出
     */
    public static ShellContribution notice(Scope scope, String sessionId, String key, Severity severity,
                                           List<UiLine> lines) {
        return new ShellContribution(scope, Kind.NOTICE, sessionId, key, severity, lines, null);
    }

    /**
     * 构造一条失效提示：告诉外壳「我贡献的内容脏了，请重新拉取」。
     * <p>
     * <b>{@code what} 只是线索，不是协议</b>：一旦外壳被要求「按 {@code what} 精确重拉某一块」，
     * 它就变成了跨边界的标识符（插件与外壳必须对同一套取值），而它的全部价值只是省一次全量重拉。
     * 取值由外壳定义、插件也拿不到那份枚举，因此这里刻意只给一段自由文本。
     * 外壳<b>可以</b>忽略它并全量重拉。
     *
     * @param scope     作用域，不可为 {@code null}
     * @param sessionId 目标会话标识；{@link Scope#SESSION} 时必填且必须已存在
     * @param what      定位线索，{@code null} 表示「我的全部贡献都脏了」
     * @return 失效贡献，保证非 {@code null}
     * @throws JellyfishException {@code scope} 为 {@code null} 时抛出
     */
    public static ShellContribution invalidated(Scope scope, String sessionId, String what) {
        return new ShellContribution(scope, Kind.INVALIDATED, sessionId, null, Severity.INFO, null, what);
    }

    /**
     * 获取作用域。
     *
     * @return 作用域，保证非 {@code null}
     */
    public Scope getScope() {
        return scope;
    }

    /**
     * 获取贡献种类。
     *
     * @return 种类，保证非 {@code null}
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 获取目标会话标识。
     *
     * @return 会话标识；{@link Scope#SHELL} 或未指定时为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取合并键。
     *
     * @return 合并键；不做合并时为 {@code null}
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取严重程度。
     *
     * @return 严重程度，保证非 {@code null}
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * 获取内容行。
     *
     * @return 不可变列表，无内容时为空列表而非 {@code null}
     */
    public List<UiLine> getLines() {
        return lines;
    }

    /**
     * 获取失效定位线索。
     *
     * @return 线索；{@link Kind#NOTICE} 时为 {@code null}
     */
    public String getWhat() {
        return what;
    }

    /**
     * <b>刻意没有 {@code equals} / {@code hashCode}</b>：{@link UiLine} 自己没有值相等
     * （{@code UiSegment} 也没有），因此按字段比对只会在「内容一样」时给出 {@code false}，
     * 那比没有更危险。与 {@code PanelContribution} 同一口径：拿身份比较，别拿它当值对象用。
     */
    @Override
    public String toString() {
        return "ShellContribution{scope=" + scope + ", kind=" + kind + ", sessionId=" + sessionId
                + ", key=" + key + ", severity=" + severity + ", lines=" + lines.size()
                + ", what=" + what + '}';
    }
}
