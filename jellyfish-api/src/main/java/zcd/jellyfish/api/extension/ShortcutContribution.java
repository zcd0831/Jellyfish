package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快捷键贡献：插件交回「我想占用哪些键」。
 * <p>
 * <b>允许返回多条</b>：一个插件常常同时提供几条相关操作（{@code Ctrl+B} 看待办、
 * {@code Ctrl+D} 标记完成），让它们挤在一个处理器里靠键位分支反而更啰嗦。
 * <p>
 * <b>冲突由内核按 {@code order} 仲裁，最小者胜</b>，被挤掉的进 {@code /ui} 的诊断清单——
 * 与面板落位（一块区域同时只显示一个）完全同一口径。插件不该自己探测「这个键有没有人用」：
 * 注册是活的，探测结果随时失效。
 * <p>
 * <b>重复键位在构造时当场报错</b>：同一个处理器返回两条 {@code ctrl+b} 是编程错误，
 * 而「谁生效」在这个范围内没有合理答案（两条都来自同一个 {@code order}），
 * 收下再让后一条静默盖掉前一条，只会让插件作者以为自己绑了两条。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ShortcutContribution {

    /** 键位 → 绑定。 */
    private final Map<String, ShortcutBinding> bindings;

    /**
     * 构造贡献。
     *
     * @param bindings 绑定列表，可为 {@code null}
     */
    private ShortcutContribution(List<ShortcutBinding> bindings) {
        Map<String, ShortcutBinding> byKey = new LinkedHashMap<String, ShortcutBinding>();
        if (bindings != null) {
            for (ShortcutBinding binding : bindings) {
                if (binding == null) {
                    continue;
                }
                ShortcutBinding previous = byKey.put(binding.getKey(), binding);
                if (previous != null) {
                    throw new JellyfishException(
                            "duplicate shortcut key in one contribution: " + binding.getKey());
                }
            }
        }
        this.bindings = Collections.unmodifiableMap(byKey);
    }

    /**
     * 构造「不占用任何键」的贡献。
     *
     * @return 贡献
     */
    public static ShortcutContribution none() {
        return new ShortcutContribution(null);
    }

    /**
     * 构造带绑定的贡献。
     *
     * @param bindings 绑定列表，可为 {@code null}
     * @return 贡献
     */
    public static ShortcutContribution of(List<ShortcutBinding> bindings) {
        return new ShortcutContribution(bindings);
    }

    /**
     * 获取全部绑定。
     *
     * @return 绑定列表（按键位去重后的顺序），可能为空但不会为 {@code null}
     */
    public List<ShortcutBinding> getBindings() {
        return Collections.unmodifiableList(new ArrayList<ShortcutBinding>(bindings.values()));
    }

    /**
     * 判断是否没有绑定。
     *
     * @return 没有绑定时返回 {@code true}
     */
    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    @Override
    public String toString() {
        return "ShortcutContribution{" + bindings.keySet() + '}';
    }
}
