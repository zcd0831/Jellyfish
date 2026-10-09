package zcd.jellyfish.infra.config;

import java.util.List;

/**
 * 数值配置项的读取口径：「非法值回退缺省，并把这件事说出来」。
 * <p>
 * <b>为什么要单独一个类</b>：这套口径此前散在四个设置类里各写一遍——
 * 回退的判定在构造函数里，而「说出来」这一半要么没有（{@code ReactSettings} 全文件没有
 * {@code Logger}），要么只写进日志（{@code SamplingSettings}），于是「配置文件里那个数字
 * 到底生效没有」在外表上看不出来：写 {@code react.maxRounds: 0} 与写 {@code 16} 跑出来一模一样。
 * 判定与措辞收在这里之后，各设置类只负责说清自己的键名与合法区间。
 * <p>
 * <b>只认「写错了」，不认「没写」</b>：{@code null} 是「这个键没配」，用缺省值是天经地义，
 * 不算问题、因此不告警——否则每份配置都会刷出一串「你没配这个键」。
 * <p>
 * <b>告警是返回值而不是副作用</b>：本类把告警文本交给调用方收集（{@code warnings()}），
 * 由 {@code RuntimeConfig} 在合并之后统一发成 {@code ConfigWarningEvent}——
 * 设置类不该知道事件面长什么样，那样每个类都会长出一条自己的发布路径。
 *
 * @author zcd
 */
final class SettingsGuard {

    /** 工具类，不实例化。 */
    private SettingsGuard() {
    }

    /**
     * 读一个要求为正整数的配置项。
     *
     * @param value    配置里原文写的值，{@code null} 表示没配
     * @param fallback 非法或缺省时使用的值
     * @param key      配置键的完整路径（例如 {@code react.maxRounds}），只用于告警文本
     * @param warnings 告警收集目标
     * @return 生效值
     */
    static int positiveOrDefault(Integer value, int fallback, String key, List<String> warnings) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            warnings.add(key + "=" + value + " 非法（要求正整数），已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        return value;
    }

    /**
     * 读一个要求非负整数的配置项（{@code 0} 的语义由各键自己定义，因此在这一层是合法的）。
     *
     * @param value    配置里原文写的值，{@code null} 表示没配
     * @param fallback 非法或缺省时使用的值
     * @param key      配置键的完整路径
     * @param warnings 告警收集目标
     * @return 生效值
     */
    static int nonNegativeOrDefault(Integer value, int fallback, String key, List<String> warnings) {
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            warnings.add(key + "=" + value + " 非法（要求非负整数），已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        return value;
    }

    /**
     * 读一个要求非负长整数的配置项。
     *
     * @param value    配置里原文写的值，{@code null} 表示没配
     * @param fallback 非法或缺省时使用的值
     * @param key      配置键的完整路径
     * @param warnings 告警收集目标
     * @return 生效值
     */
    static long nonNegativeOrDefault(Long value, long fallback, String key, List<String> warnings) {
        if (value == null) {
            return fallback;
        }
        if (value < 0L) {
            warnings.add(key + "=" + value + " 非法（要求非负整数），已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        return value;
    }

    /**
     * 读一个百分比配置项：非负合法，超过 100 按 100 处理。
     * <p>
     * 「超过 100」与「负数」分开说：前者是写大了（多半想表达「尽量晚点压」），
     * 后者才是写错，两者的修法不一样，因此不合成一句话。
     *
     * @param value    配置里原文写的值，{@code null} 表示没配
     * @param fallback 非法或缺省时使用的值
     * @param key      配置键的完整路径
     * @param warnings 告警收集目标
     * @return 生效值，落在 {@code [0, 100]}
     */
    static int percentOrDefault(Integer value, int fallback, String key, List<String> warnings) {
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            warnings.add(key + "=" + value + " 非法（要求非负整数），已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        if (value > 100) {
            warnings.add(key + "=" + value + " 超出上限（最大 100），已按 100 处理");
            return 100;
        }
        return value;
    }
}
