package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.RuntimeInfo;

/**
 * 工具行渲染请求：外壳要渲染一条工具轨迹行时，问「这个工具的行该怎么显示」。
 * <p>
 * <b>注册方式</b>：{@code handle}，路由键 = <b>工具名</b>。一个工具行的样式只能有一套——
 * 与工具调用一样「一个名字对应一个实现」，因此这里不带 {@code order}、也不讨论多插件共存。
 * <p>
 * <b>调用时机</b>：外壳渲染轨迹行时逐工具名问一次，且外壳按「内容失效时才重问」的模型缓存结果
 * （不是每帧）。轨迹行按 {@code toolCallId} 与工具名配对，因此同一个工具名在一屏里出现多次也
 * 只需要一次询问——路由键是工具名而不是调用标识。
 * <p>
 * <b>0 个处理器时</b>：{@link ToolRenderHint#none()}，工具行与没有这个扩展点时逐字段一致。
 * <p>
 * <b>失败语义</b>：处理器抛错 → 只记 WARN、按 {@link ToolRenderHint#none()} 处理。
 * 一个插件的渲染提示不该让轨迹行消失——那是用户唯一的执行现场记录。
 * <p>
 * <b>实现约定</b>：由外壳在<b>渲染线程</b>内联调用，因此处理器必须<b>快且只读</b>——不要做 I/O、
 * 不要阻塞、不要发布事件。渲染线程一卡，整个界面都不动。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolRenderHintRequest extends ExtensionRequest<ToolRenderHint> {

    /** 工具名，即路由键。 */
    private final String toolName;

    /** 工具描述符，插件无需再去注册表回查。 */
    private final ToolDescriptor descriptor;

    /** 外壳的运行时信息：只在某一种外壳里有意义的提示应当据此前置判断。 */
    private final RuntimeInfo shell;

    /**
     * 构造请求。
     *
     * @param toolName   工具名，不可为空白
     * @param descriptor 工具描述符，可为 {@code null}
     * @param shell      外壳运行时信息，可为 {@code null}（按 {@link RuntimeInfo#unknown()} 理解）
     */
    public ToolRenderHintRequest(String toolName, ToolDescriptor descriptor, RuntimeInfo shell) {
        super(ToolRenderHint.class, null);
        this.toolName = toolName;
        this.descriptor = descriptor;
        this.shell = shell == null ? RuntimeInfo.unknown() : shell;
    }

    @Override
    public String getRouteKey() {
        return toolName;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具描述符。
     *
     * @return 工具描述符，可能为 {@code null}
     */
    public ToolDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * 获取外壳运行时信息。
     * <p>
     * 面板与轨迹行只有 {@code -tui} 会渲染，因此这条信息主要用于「这个外壳根本不显示它，
     * 就不必表态」这类前置判断。
     *
     * @return 外壳运行时信息，保证非 {@code null}
     */
    public RuntimeInfo getShell() {
        return shell;
    }
}
