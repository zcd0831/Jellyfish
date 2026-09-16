package zcd.jellyfish.infra.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 指标快照：某一时刻的全部计数与仪表值，不可变。
 * <p>
 * 与 {@link MetricsRegistry} 分开是刻意的：注册表是可变的写入端，快照是不可变的读取端，
 * 展示方（日志、命令输出）只依赖快照，因此不需要面对并发写入。
 *
 * @author zcd
 */
public final class MetricsSnapshot {

    /** 计数（单调递增），按名字升序。 */
    private final Map<String, Long> counters;

    /** 仪表（现读值），按名字升序。 */
    private final Map<String, Long> gauges;

    /**
     * 构造快照。
     *
     * @param counters 计数，可为 {@code null}
     * @param gauges   仪表，可为 {@code null}
     */
    MetricsSnapshot(Map<String, Long> counters, Map<String, Long> gauges) {
        this.counters = counters == null
                ? Collections.<String, Long>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Long>(counters));
        this.gauges = gauges == null
                ? Collections.<String, Long>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Long>(gauges));
    }

    /**
     * 获取全部计数。
     *
     * @return 不可修改映射，按名字升序；可能为空但不会为 {@code null}
     */
    public Map<String, Long> getCounters() {
        return counters;
    }

    /**
     * 获取全部仪表值。
     *
     * @return 不可修改映射，按名字升序；可能为空但不会为 {@code null}
     */
    public Map<String, Long> getGauges() {
        return gauges;
    }

    /**
     * 判断快照是否为空（既没有计数也没有仪表）。
     *
     * @return 两者皆空返回 {@code true}
     */
    public boolean isEmpty() {
        return counters.isEmpty() && gauges.isEmpty();
    }

    /**
     * 渲染为多行可读文本：先计数后仪表，各自按名字升序。
     *
     * @return 可读文本；无指标时为一句说明
     */
    public String render() {
        if (isEmpty()) {
            return "（暂无指标）";
        }
        StringBuilder text = new StringBuilder();
        appendSection(text, "计数", counters);
        appendSection(text, "仪表", gauges);
        return text.toString();
    }

    /**
     * 追加一个分区。
     *
     * @param text   目标
     * @param title  分区标题
     * @param values 分区内容
     */
    private static void appendSection(StringBuilder text, String title, Map<String, Long> values) {
        if (values.isEmpty()) {
            return;
        }
        // 用 TreeMap 保证渲染顺序稳定，便于日志 diff 与人工比对
        for (Map.Entry<String, Long> entry : new TreeMap<String, Long>(values).entrySet()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append('[').append(title).append("] ")
                    .append(entry.getKey()).append('=').append(entry.getValue());
        }
    }
}
