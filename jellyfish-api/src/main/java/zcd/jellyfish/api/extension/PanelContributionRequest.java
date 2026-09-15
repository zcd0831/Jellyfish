package zcd.jellyfish.api.extension;

/**
 * 面板贡献请求：外壳在刷新界面时构造，询问「这个会话还有没有要常驻显示的面板」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：同一会话允许多个插件各给一块面板，
 * 因此用 {@code PluginContext.contribute} 注册，外壳按 {@code order} 升序收集。
 * <p>
 * <b>每个插件在同一区域至多一块</b>：同一插件注册多个面板处理器时，外壳只取 {@code order} 最小的一个，
 * 其余记告警——否则一个插件就能用自己的面板把界面刷满，而这本该是用户用 {@code /ui} 决定的事。
 * <p>
 * <b>调用时机与三条硬约束</b>：与 {@link StatusLineContributionRequest} 完全一致——
 * 外壳只在缓存失效时收集（不每帧询问），处理器必须<b>纯只读、不得发布 {@code UiInvalidatedEvent}、
 * 必须快</b>（它在渲染线程内联执行）。<b>外壳也可能永远不会问</b>：{@code -cli} 单次模式没有界面。
 * <p>
 * <b>落了哪个区域由外壳决定</b>：结果里的 {@code preferredRegion} 只是建议，外壳可以忽略；
 * 插件不能假设自己一定显示、也不能假设显示在自己想的位置。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PanelContributionRequest extends ExtensionRequest<PanelContribution> {

    /**
     * 构造面板贡献请求。
     *
     * @param sessionId 会话标识，可为 {@code null}
     */
    public PanelContributionRequest(String sessionId) {
        super(PanelContribution.class, sessionId);
    }

    @Override
    public String getRouteKey() {
        return null;
    }
}
