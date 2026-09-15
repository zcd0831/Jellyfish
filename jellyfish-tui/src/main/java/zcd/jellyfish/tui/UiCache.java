package zcd.jellyfish.tui;

import zcd.jellyfish.infra.ui.UiContributions;
import zcd.jellyfish.infra.ui.UiSnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 插件 UI 贡献的帧间缓存。
 * <p>
 * <b>为什么要缓存而不是每帧收集</b>：外壳每 40ms 渲染一帧（TamboUI 的 tick），若每帧都去问插件，
 * 空闲时也在反复调用插件处理器。缓存把「询问」压缩到「有理由相信内容变了」的时刻，
 * 代价是必须把失效触发源记全——漏一个，插件内容就永久陈旧。
 * <p>
 * <b>为什么失效用版本号而不是布尔脏标记</b>：布尔标记在下面这个竞态下会丢信息——
 * 渲染线程刚读到 {@code dirty=false} 并开始收集，事件线程此时置位 {@code dirty=true}，
 * 收集结束时又把它清成 {@code false}，那次失效就静默消失了。改成「先读版本、收集后回写版本」后，
 * 收集期间发生的失效会让版本对不上，下一帧自然再收集一次。
 * <p>
 * <b>线程契约</b>：{@link #invalidate()} 可能在事件通道的订阅者线程被调用（插件发布
 * {@code UiInvalidatedEvent} 之后），因此版本号是 {@link AtomicLong}；
 * 而 {@link #snapshot(String)} 只在渲染线程调用，缓存字段本身不需要同步。
 * <p>
 * <b>会话标识也参与比对</b>：哪怕调用点忘了在会话切换时置失效，这里也能兜住——
 * 与 {@code TuiApp} 显式置失效是双保险，代价只有一次 {@code equals}。
 *
 * @author zcd
 */
final class UiCache {

    /** 贡献收集门面。 */
    private final UiContributions contributions;

    /** 失效版本号：每次 {@link #invalidate()} 自增，跨线程读写。 */
    private final AtomicLong version = new AtomicLong();

    /** 上次收集的结果，只由渲染线程读写。 */
    private UiSnapshot cached = UiSnapshot.empty();

    /** 上次收集时的会话标识，只由渲染线程读写。 */
    private String cachedSessionId;

    /** 是否收集过至少一次，只由渲染线程读写。 */
    private boolean collected;

    /** 上次收集<b>开始前</b>读到的版本号，只由渲染线程读写。 */
    private long collectedVersion;

    /**
     * 构造缓存。
     *
     * @param contributions 贡献收集门面，不可为 {@code null}
     */
    UiCache(UiContributions contributions) {
        this.contributions = Objects.requireNonNull(contributions, "contributions must not be null");
    }

    /**
     * 标记内容可能已过期：下一次 {@link #snapshot(String)} 会重新收集。
     * <p>
     * 廉价、线程安全，可以在任意线程、任意频率调用；连续调用只多收集一次，
     * 但<b>不会丢掉任何一次失效</b>（见类注释里的竞态说明）。
     */
    void invalidate() {
        version.incrementAndGet();
    }

    /**
     * 取本帧要用的快照，必要时重新收集。
     * <p>
     * 只在渲染线程调用：命中缓存时零开销，失效时在调用点线程内联执行插件处理器。
     *
     * @param sessionId 当前会话标识，可为 {@code null}
     * @return 快照，保证非 {@code null}
     */
    UiSnapshot snapshot(String sessionId) {
        long current = version.get();
        if (!collected || current != collectedVersion || !Objects.equals(cachedSessionId, sessionId)) {
            cached = contributions.collect(sessionId);
            cachedSessionId = sessionId;
            collected = true;
            // 回写的是收集开始前的版本：期间发生的失效会让两者不等，下一帧再收集
            collectedVersion = current;
        }
        return cached;
    }
}
