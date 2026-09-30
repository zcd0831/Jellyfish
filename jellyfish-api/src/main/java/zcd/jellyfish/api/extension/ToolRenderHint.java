package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.ui.UiEmphasis;

/**
 * 工具行渲染提示：插件对「某一行的工具轨迹怎么显示」表一次态。
 * <p>
 * <b>解决什么问题</b>：工具轨迹行完全由外壳渲染，插件够不着。而有些工具的呈现方式是<b>它的作者</b>
 * 比外壳更清楚的：{@code heartbeat} 的参数是纯噪音，MCP 的大入参默认不该铺开，
 * 一个「检查点」工具值得用强调档位顶出来。今天这些都做不到。
 * <p>
 * <b>与 {@code ToolMetadata.KEY_SUMMARY} 的分工</b>：{@code summary} 回答「这一行<b>说什么</b>」
 * （是工具自己写进结果元数据的一句话），本类型回答「这一行<b>怎么显示</b>」。
 * 两者受众不同、<b>互不解析</b>：本类型<b>改不了</b>轨迹行的文本内容——让渲染器去改写工具写的那句话，
 * 同一条信息就有两个真源。这也是没有 {@code text} 字段的原因。
 * <p>
 * <b>三态字段，与内核既有的「不表态」纪律一致</b>：每个字段为 {@code null} 表示「这个字段我不表态，
 * 保持外壳的缺省」。因此 {@link #none()}（三项都不表态）与「完全没有这个扩展点」逐字段一致——
 * 而在一个「只想让参数默认收起来」的插件手里，另外两项不必跟着写一遍外壳的缺省值。
 * <p>
 * <b>它只影响显示</b>：折叠与否、强调与否，都不改变任何执行路径、不改变发给模型的内容、
 * 也不改变权限判定。工具行是给用户看的。
 * <p>
 * <b>不提供自定义组件</b>：{@code jellyfish-api} 零依赖，而渲染引擎在插件与内核里不是同一个
 * {@code Class}（PF4J 的子优先类加载器），回传必然 {@code ClassCastException}。
 * 因此插件只能选用已有的词汇。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolRenderHint {

    /** 强调档位；{@code null} 表示不表态。 */
    private final UiEmphasis emphasis;

    /** 参数行是否默认折叠；{@code null} 表示不表态。 */
    private final Boolean collapsedByDefault;

    /** 是否显示参数行；{@code null} 表示不表态。 */
    private final Boolean showArguments;

    /**
     * 构造提示。
     *
     * @param emphasis           强调档位，可为 {@code null}（不表态）
     * @param collapsedByDefault 参数行是否默认折叠，可为 {@code null}（不表态）
     * @param showArguments      是否显示参数行，可为 {@code null}（不表态）
     */
    private ToolRenderHint(UiEmphasis emphasis, Boolean collapsedByDefault, Boolean showArguments) {
        this.emphasis = emphasis;
        this.collapsedByDefault = collapsedByDefault;
        this.showArguments = showArguments;
    }

    /**
     * 构造「不表态」的提示：工具行与没有这个扩展点时逐字段一致。
     *
     * @return 提示
     */
    public static ToolRenderHint none() {
        return new ToolRenderHint(null, null, null);
    }

    /**
     * 构造一条提示，未表态的字段传 {@code null}。
     *
     * @param emphasis           强调档位，可为 {@code null}（不表态，保持外壳的档位）
     * @param collapsedByDefault 参数行是否默认折叠，可为 {@code null}（不表态，按外壳缺省）
     * @param showArguments      是否显示参数行，可为 {@code null}（不表态，按外壳缺省）
     * @return 提示
     */
    public static ToolRenderHint of(UiEmphasis emphasis, Boolean collapsedByDefault, Boolean showArguments) {
        return new ToolRenderHint(emphasis, collapsedByDefault, showArguments);
    }

    /**
     * 获取强调档位。
     *
     * @return 档位；不表态时为 {@code null}
     */
    public UiEmphasis getEmphasis() {
        return emphasis;
    }

    /**
     * 获取「参数行是否默认折叠」。
     *
     * @return 结论；不表态时为 {@code null}
     */
    public Boolean getCollapsedByDefault() {
        return collapsedByDefault;
    }

    /**
     * 获取「是否显示参数行」。
     *
     * @return 结论；不表态时为 {@code null}
     */
    public Boolean getShowArguments() {
        return showArguments;
    }

    /**
     * 判断是否三个字段都不表态。
     *
     * @return 都不表态返回 {@code true}
     */
    public boolean isEmpty() {
        return emphasis == null && collapsedByDefault == null && showArguments == null;
    }

    @Override
    public String toString() {
        return "ToolRenderHint{emphasis=" + emphasis + ", collapsedByDefault=" + collapsedByDefault
                + ", showArguments=" + showArguments + '}';
    }
}
