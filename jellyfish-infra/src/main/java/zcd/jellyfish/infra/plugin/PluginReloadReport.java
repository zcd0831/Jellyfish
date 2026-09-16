package zcd.jellyfish.infra.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 插件重载报告：一次配置重载里，插件运行时实际做了什么。
 * <p>
 * 内部可变、对外只读——报告在重载过程中逐项累积，读取方（命令输出、事件、日志）只关心结果。
 * 四个列表分开而不是合成一个「变更数」，是因为它们对用户的含义完全不同：
 * 重新启动意味着「配置段变了」、启动 / 停止意味着「名单变了」、失败意味着「需要人看一眼」。
 *
 * @author zcd
 */
public final class PluginReloadReport {

    /** 本次新启动的插件。 */
    private final List<String> started = new ArrayList<String>();

    /** 本次停止的插件。 */
    private final List<String> stopped = new ArrayList<String>();

    /** 本次因配置段变化而重启的插件。 */
    private final List<String> restarted = new ArrayList<String>();

    /** 本次启动失败的插件。 */
    private final List<String> failed = new ArrayList<String>();

    /**
     * 构造空报告。
     */
    public PluginReloadReport() {
    }

    /**
     * 构造「什么都没变」的报告。
     *
     * @return 空报告
     */
    static PluginReloadReport empty() {
        return new PluginReloadReport();
    }

    /**
     * 记录一个插件已启动。
     *
     * @param pluginId 插件标识
     */
    void recordStarted(String pluginId) {
        started.add(pluginId);
    }

    /**
     * 记录一个插件已停止。
     *
     * @param pluginId 插件标识
     */
    void recordStopped(String pluginId) {
        stopped.add(pluginId);
    }

    /**
     * 记录一个插件已因配置段变化而重启。
     *
     * @param pluginId 插件标识
     */
    void recordRestarted(String pluginId) {
        restarted.add(pluginId);
    }

    /**
     * 记录一个插件启动失败。
     *
     * @param pluginId 插件标识
     */
    void recordFailed(String pluginId) {
        failed.add(pluginId);
    }

    /**
     * 获取本次新启动的插件。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public List<String> getStarted() {
        return Collections.unmodifiableList(started);
    }

    /**
     * 获取本次停止的插件。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public List<String> getStopped() {
        return Collections.unmodifiableList(stopped);
    }

    /**
     * 获取本次因配置段变化而重启的插件。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public List<String> getRestarted() {
        return Collections.unmodifiableList(restarted);
    }

    /**
     * 获取本次启动失败的插件。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public List<String> getFailed() {
        return Collections.unmodifiableList(failed);
    }

    /**
     * 汇总「被动过」的全部插件标识（启动 ∪ 停止 ∪ 重启），供事件与指标使用。
     *
     * @return 不可修改集合，按首次出现顺序；可能为空但不会为 {@code null}
     */
    public Set<String> touchedPluginIds() {
        Set<String> touched = new LinkedHashSet<String>();
        touched.addAll(restarted);
        touched.addAll(started);
        touched.addAll(stopped);
        touched.addAll(failed);
        return Collections.unmodifiableSet(touched);
    }

    /**
     * 判断本次重载是否什么都没做（用于日志降噪）。
     *
     * @return 四个列表皆空返回 {@code true}
     */
    public boolean isEmpty() {
        return started.isEmpty() && stopped.isEmpty() && restarted.isEmpty() && failed.isEmpty();
    }
}
