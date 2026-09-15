package zcd.jellyfish.api.extension;

/**
 * 状态栏贡献请求：外壳在刷新状态栏前构造，询问「这个会话还有没有要显示的片段」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：同一会话允许多个插件各给一段，
 * 因此用 {@code PluginContext.contribute} 注册，外壳按 {@code order} 升序收集并拼接。
 * <p>
 * <b>调用时机</b>：外壳<b>不</b>每帧询问（那样空闲时也在反复调用处理器），只在缓存失效时收集一次：
 * 会话切换、回合开始/收敛、命令执行、插件加载或卸载、以及插件主动发布的
 * {@code UiInvalidatedEvent}。
 * <p>
 * <b>由此产生的三条硬约束（处理器必须满足）</b>：
 * <ol>
 *     <li><b>纯只读</b>：只读插件自己的内存状态，不做文件/网络等 I/O，不产生任何副作用——
 *     处理器会被任一失效触发反复调用，把 I/O 写进来就等于把界面帧率绑在磁盘上；</li>
 *     <li><b>不得发布 {@code UiInvalidatedEvent}</b>：那会形成「失效 → 收集 → 失效」的死循环；</li>
 *     <li><b>必须快</b>：处理器外壳在渲染线程内联执行，慢处理器直接卡住界面。</li>
 * </ol>
 * <p>
 * <b>外壳可能永远不会问</b>：{@code -cli} 单次模式没有界面，根本没有调用点。插件不能假设
 * 「我贡献了就一定显示」，这与 {@code PromptContributionRequest} 的边界一致。
 * <p>
 * 不经此处的插件不受影响：没有处理器时外壳不显示任何插件片段。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class StatusLineContributionRequest extends ExtensionRequest<StatusLineContribution> {

    /**
     * 构造状态栏贡献请求。
     *
     * @param sessionId 会话标识，可为 {@code null}
     */
    public StatusLineContributionRequest(String sessionId) {
        super(StatusLineContribution.class, sessionId);
    }

    @Override
    public String getRouteKey() {
        return null;
    }
}
