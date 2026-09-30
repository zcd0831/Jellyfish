package zcd.jellyfish.tui;

import zcd.jellyfish.infra.ui.OwnedShortcut;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件键位表：把插件声明的绑定仲裁成「按哪个键执行哪条命令」。
 * <p>
 * <b>仲裁口径与面板落位一致</b>：绑定按 {@code order} 升序到达，<b>先到者胜</b>；
 * 抢同一个键的后来者进候选清单，由 {@code /ui} 报出来——「我绑的键怎么没反应」必须能查出是谁占了。
 * <p>
 * <b>指向不存在命令的绑定在此剔除</b>（记入候选清单，带原因）。这一条<b>刻意不放在注册时</b>：
 * 注册是活的，插件在自己的 {@code start()} 里先注册命令还是先注册快捷键、以及跨插件指向
 * （A 的键执行 B 的命令），都会让「此刻命令在不在」变成一个随加载顺序变化的答案。放到这里之后，
 * 判据变成「插件全部就绪之后命令在不在」——与收集模型同构，也与顺序无关；
 * 而顺序写反的后果只是「这条快捷键不生效」，不是插件启动失败。
 * <p>
 * 不可变，可安全跨线程传递（实际只在渲染线程使用）。
 *
 * @author zcd
 */
final class PluginShortcuts {

    /** 空表：没有插件声明键位时的取值，键盘行为与没有这个扩展点逐字段一致。 */
    static final PluginShortcuts NONE = new PluginShortcuts(
            Collections.<String, String>emptyMap(), Collections.<String>emptyList());

    /** 生效的键位表：键位 → 命令名。 */
    private final Map<String, String> commands;

    /** 没生效的说明行（被抢 / 命令不存在），给 {@code /ui} 用。 */
    private final List<String> candidates;

    /**
     * 构造。
     *
     * @param commands   生效的键位表
     * @param candidates 没生效的说明行
     */
    private PluginShortcuts(Map<String, String> commands, List<String> candidates) {
        this.commands = Collections.unmodifiableMap(commands);
        this.candidates = Collections.unmodifiableList(candidates);
    }

    /**
     * 把插件声明的绑定仲裁成键位表。
     *
     * @param bindings     带来源的绑定列表（{@code order} 升序），可为 {@code null}
     * @param commandNames 当前可用的命令名（含别名），可为 {@code null}
     * @return 键位表，保证非 {@code null}
     */
    static PluginShortcuts resolve(List<OwnedShortcut> bindings, Set<String> commandNames) {
        if (bindings == null || bindings.isEmpty()) {
            return NONE;
        }
        Set<String> available = commandNames == null
                ? Collections.<String>emptySet() : new LinkedHashSet<String>(commandNames);
        Map<String, String> commands = new LinkedHashMap<String, String>();
        List<String> candidates = new ArrayList<String>();
        for (OwnedShortcut owned : bindings) {
            String key = owned.getBinding().getKey();
            if (!available.contains(owned.getBinding().getCommandName())) {
                candidates.add(describe(owned) + "（命令不存在）");
                continue;
            }
            String winner = commands.get(key);
            if (winner != null) {
                candidates.add(describe(owned) + "（键位已被 " + winner + " 占用）");
                continue;
            }
            commands.put(key, owned.getBinding().getCommandName());
        }
        return new PluginShortcuts(commands, candidates);
    }

    /**
     * 取某个键位要执行的命令。
     *
     * @param key 规范化后的键位（形如 {@code ctrl+a}），可为 {@code null}
     * @return 命令名；没有绑定或已失效时返回 {@code null}
     */
    String commandOf(String key) {
        return key == null ? null : commands.get(key);
    }

    /**
     * 判断是否有任何插件键位。
     *
     * @return 没有绑定时返回 {@code true}
     */
    boolean isEmpty() {
        return commands.isEmpty();
    }

    /**
     * 取没生效的说明行。
     *
     * @return 说明行列表，可能为空但不会为 {@code null}
     */
    List<String> getCandidates() {
        return candidates;
    }

    /**
     * 取生效的键位表。
     *
     * @return 不可修改的映射（键位 → 命令名），可能为空但不会为 {@code null}
     */
    Map<String, String> getCommands() {
        return commands;
    }

    /**
     * 描述一条没生效的绑定。
     *
     * @param owned 绑定
     * @return 说明文本
     */
    private static String describe(OwnedShortcut owned) {
        return owned.getBinding().getKey() + " -> /" + owned.getBinding().getCommandName()
                + "（" + owned.getOwner() + "）";
    }
}
