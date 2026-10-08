package zcd.jellyfish.api.plugin;

/**
 * 插件配置段的来源层级。
 * <p>
 * <b>为什么插件需要知道这件事</b>：{@code jellyfish.json} 有两级——全局级（{@code ~/.jellyfish/}，
 * 只属于用户自己）与项目级（{@code ./.jellyfish/}，随仓库走）。两级合并时<b>同名插件段整对象替换</b>，
 * 因此一个插件段要么来自这一级、要么来自那一级，不会混合。
 * <p>
 * 而有一类配置键的作用是「收紧一个安全边界」——提示内联的上限、加载目录的范围。这类键不能由
 * 随仓库变化的内容决定：一个 {@code git clone} 下来的目录就能改宽它，等于「配置即代码」。
 * 插件据此判定「这个值该不该采纳」，见 {@link PluginContext#globalConfiguration()}。
 * <p>
 * {@link #UNKNOWN} 是给「不区分来源的容器」留的兼容口（旧内核、手写装配、测试桩）：
 * 此时插件只能按合并值处理，也就是行为退回改造前。真实内核会如实返回。
 *
 * @author zcd
 */
public enum PluginConfigScope {

    /** 两级都没有这个插件的配置段。 */
    ABSENT,

    /** 只有全局级声明了，与「在哪个仓库里启动」无关。 */
    GLOBAL,

    /** 项目级声明了（整对象替换了全局级）。 */
    PROJECT,

    /** 容器没有提供来源信息；按合并值处理。 */
    UNKNOWN
}
