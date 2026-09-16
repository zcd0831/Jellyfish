package zcd.jellyfish.infra.metrics;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import zcd.jellyfish.api.JellyfishException;

/**
 * 指标注册表：内核可观测性的写入端。
 * <p>
 * 只有两种指标，够用且不会长出第二套语义：
 * <ul>
 *     <li><b>计数</b>（counter）：单调递增，用 {@link LongAdder} 承载，适合高并发自增；</li>
 *     <li><b>仪表</b>（gauge）：现读值，注册一个 {@link Supplier}，快照时才求值——
 *     事件通道的队列长度这类值本来就没有「累加」语义。</li>
 * </ul>
 * <p>
 * <b>刻意不引第三方指标库</b>（Micrometer 等）：本项目对内核依赖的态度是「能用 JDK 就不加库」，
 * 而这里需要的只是计数、现读与渲染三件事；接外部指标系统时再加一个导出适配器即可，
 * 不必在此之前把内核的类型系统绑到别人身上。
 * <p>
 * <b>自身指标不通过事件通道上报</b>：避免「上报指标本身产生事件」的自指循环；
 * 需要展示时由上层主动调用 {@link #snapshot()}（与 {@code EventChannelStats} 同口径）。
 * <p>
 * 线程安全，可安全被多个 react / 渲染 / 事件线程并发写入。
 *
 * @author zcd
 */
@Singleton
public final class MetricsRegistry {

    /** 计数：名字 → 累加器。 */
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<String, LongAdder>();

    /** 仪表：名字 → 现读取值函数。 */
    private final Map<String, Supplier<Number>> gauges = new ConcurrentHashMap<String, Supplier<Number>>();

    /**
     * 构造空注册表。
     */
    @Inject
    public MetricsRegistry() {
    }

    /**
     * 计数加一。
     *
     * @param name 指标名，不可为空白
     */
    public void increment(String name) {
        add(name, 1L);
    }

    /**
     * 计数累加。
     *
     * @param name  指标名，不可为空白
     * @param delta 增量
     */
    public void add(String name, long delta) {
        counter(name).add(delta);
    }

    /**
     * 取得某个计数的累加器（不存在时创建）。
     *
     * @param name 指标名，不可为空白
     * @return 累加器，保证非 {@code null}
     */
    public LongAdder counter(String name) {
        final String key = requireName(name);
        LongAdder existing = counters.get(key);
        if (existing != null) {
            return existing;
        }
        LongAdder created = new LongAdder();
        LongAdder raced = counters.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    /**
     * 注册（或替换）一个仪表。
     * <p>
     * 重复注册同名仪表按最后一次生效：仪表是「现读某个外部对象的状态」，
     * 同一个名字对应多个来源本身就是配置错误，不值得为它设计一套冲突处理。
     *
     * @param name     指标名，不可为空白
     * @param supplier 现读取值函数，不可为 {@code null}
     */
    public void gauge(String name, Supplier<Number> supplier) {
        gauges.put(requireName(name), Objects.requireNonNull(supplier, "supplier must not be null"));
    }

    /**
     * 生成当前指标快照。
     * <p>
     * 仪表在此时求值：单个取数函数抛错只跳过它自己（记 {@code null} → 不出现），
     * 否则一个坏仪表会让整份诊断输出消失。
     *
     * @return 不可变快照
     */
    public MetricsSnapshot snapshot() {
        Map<String, Long> counterValues = new TreeMap<String, Long>();
        for (Map.Entry<String, LongAdder> entry : counters.entrySet()) {
            counterValues.put(entry.getKey(), entry.getValue().sum());
        }
        Map<String, Long> gaugeValues = new TreeMap<String, Long>();
        for (Map.Entry<String, Supplier<Number>> entry : gauges.entrySet()) {
            Number value = readGauge(entry.getKey(), entry.getValue());
            if (value != null) {
                gaugeValues.put(entry.getKey(), value.longValue());
            }
        }
        return new MetricsSnapshot(counterValues, gaugeValues);
    }

    /**
     * 读取一个仪表的值，取数失败时返回 {@code null}。
     *
     * @param name     指标名
     * @param supplier 取数函数
     * @return 取值；失败或返回 {@code null} 时为 {@code null}
     */
    private static Number readGauge(String name, Supplier<Number> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            // 诊断输出必须比它诊断的对象更稳：坏仪表只丢它自己
            return null;
        }
    }

    /**
     * 校验指标名非空白。
     *
     * @param name 指标名
     * @return 原样返回
     */
    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new JellyfishException("metric name must not be blank");
        }
        return name;
    }
}
