package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * UI 贡献失效事件：插件改了它贡献给外壳界面的内容后广播，告诉外壳「你缓存的我的那块已经过期了」。
 * <p>
 * <b>为什么需要它</b>：外壳<b>不</b>每帧去问插件（那样空闲时也在反复调用插件处理器），
 * 而是只在「有理由相信内容变了」时才重新收集。会话切换、回合开始/收敛、命令执行、插件加载卸载
 * 这些理由外壳自己知道，唯独「插件内部状态自发变化」它看不到——本事件就是插件说出这件事的唯一通道。
 * <p>
 * <b>它是「缓存失效」信号，不是「渲染指令」</b>：外壳可以合并多次失效、可以忽略本事件
 * （例如它自己那套兜底触发源已经覆盖），也<b>可能根本不订阅</b>（如 {@code -cli} 单次模式根本没有界面）。
 * 因此插件不能假设「发了就一定重绘」，这与 {@code PromptContributionRequest} 的
 * 「不能假设一定被询问」是同一种边界。
 * <p>
 * <b>为什么刻意不带字段</b>：不带 {@code pluginId}，是为了不给「按 owner 分片缓存」这种优化留暗门——
 * 当前实现是全量重问，而 {@code PluginContext.emit} 本身也不携带来源标记，带一个插件自报的
 * {@code pluginId} 只会让人误以为它可信。将来若真要分片，给本类加字段是兼容改动。
 * <p>
 * <b>不要在处理 UI 贡献的处理器里发本事件</b>：那会形成「失效 → 收集 → 失效」的死循环。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class UiInvalidatedEvent extends AbstractJellyfishEvent {

    /**
     * 构造 UI 贡献失效事件。
     * <p>
     * 刻意是进程级事件（{@code sessionId} 为 {@code null}）：外壳的失效是「整块缓存作废」，
     * 与哪个会话无关，按会话过滤只会让「插件在别的会话里改了状态」这种正当情况被漏掉。
     */
    public UiInvalidatedEvent() {
        super(null);
    }
}
