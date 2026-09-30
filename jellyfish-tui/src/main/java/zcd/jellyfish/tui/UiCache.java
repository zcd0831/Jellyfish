package zcd.jellyfish.tui;

import zcd.jellyfish.api.extension.ToolRenderHint;
import zcd.jellyfish.infra.ui.OwnedShortcut;
import zcd.jellyfish.infra.ui.UiContributions;
import zcd.jellyfish.infra.ui.UiSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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

    /** 工具行渲染提示（按工具名），只由渲染线程读写。 */
    private final Slot<Map<String, ToolRenderHint>> hints =
            new Slot<Map<String, ToolRenderHint>>();

    /** 插件声明的快捷键绑定，只由渲染线程读写。 */
    private final Slot<List<OwnedShortcut>> shortcuts = new Slot<List<OwnedShortcut>>();

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
     * 取工具行渲染提示，必要时重新问一遍插件。
     * <p>
     * <b>为什么与面板快照分开缓存</b>：提示按工具名索引、与会话无关，而快照按会话取。
     * 合成一份会让每次切换会话都白问一遍全部工具——那是一张按工具数增长的查询，
     * 而它本来只需要在「插件内容可能变了」时重问。
     * <p>
     * <b>返回的是同一个实例直到失效</b>：调用方（{@code ChatState}）按实例比对来判断
     * 「提示有没有变」，因此这里不能每次新建一张内容相同的表。
     *
     * @return 工具名 → 提示；无贡献时为空映射而非 {@code null}
     */
    Map<String, ToolRenderHint> hints() {
        return hints.get(version.get(), new Supplier<Map<String, ToolRenderHint>>() {

            @Override
            public Map<String, ToolRenderHint> get() {
                return contributions.toolRenderHints();
            }
        });
    }

    /**
     * 取插件声明的快捷键绑定，必要时重新问一遍插件。
     * <p>
     * 与 {@link #hints()} 同一种缓存口径：按工具数 / 插件数增长，且与会话无关，
     * 因此只在「内容可能变了」时重问，并且<b>失效前返回同一个实例</b>——
     * 调用方据此判断「要不要重新仲裁键位表」。
     *
     * @return 带来源的绑定列表（{@code order} 升序），无贡献时为空列表
     */
    List<OwnedShortcut> shortcutBindings() {
        return shortcuts.get(version.get(), new Supplier<List<OwnedShortcut>>() {

            @Override
            public List<OwnedShortcut> get() {
                return contributions.shortcuts();
            }
        });
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

    /**
     * 按版本号缓存的一格。
     * <p>
     * <b>回写的是「取用开始前」的版本</b>：若在插件处理器执行期间发生了失效，
     * 两者就不再相等，下一帧自然再取一次——这正是 {@link #version} 用版本号而不是布尔脏标记的理由。
     *
     * @param <T> 内容类型
     * @author zcd
     */
    private static final class Slot<T> {

        /** 上次取用<b>开始前</b>读到的版本号。 */
        private long version = -1L;

        /** 缓存的内容，{@code null} 表示还没取过。 */
        private T value;

        /**
         * 取内容，必要时重新加载。
         *
         * @param current 当前版本号
         * @param loader  加载函数
         * @return 内容，保证非 {@code null}
         */
        T get(long current, Supplier<T> loader) {
            if (value == null || version != current) {
                value = loader.get();
                version = current;
            }
            return value;
        }
    }
}
