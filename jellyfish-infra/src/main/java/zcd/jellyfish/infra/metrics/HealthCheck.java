package zcd.jellyfish.infra.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 健康检查汇总：按注册顺序逐项执行，单项失败不影响其它项。
 * <p>
 * <b>单项失败只降级为一项 {@code DOWN}</b>：某个检查项实现抛错时，报告里出现的是
 * 「这一项不可用」，而不是整份诊断输出消失——诊断工具的失败方式必须比它诊断的对象更可控。
 * <p>
 * 检查项由装配根注入，因此 {@code infra} 与 {@code core} 可以各自贡献自己那一层能看见的东西，
 * 不必让低层为了报告高层状态而反向依赖。
 *
 * @author zcd
 */
public final class HealthCheck {

    /** 检查项，按注册顺序执行。 */
    private final List<HealthIndicator> indicators;

    /**
     * 构造健康检查。
     * <p>
     * 由装配根显式装配检查项列表：{@code core} 的检查项要一起进来，因此不适合让本类自己去
     * 依赖注入集合（那会把「装配哪些检查项」变成注解层面的隐式约定）。
     *
     * @param indicators 检查项，可为 {@code null}（等价于没有检查项）
     */
    public HealthCheck(List<HealthIndicator> indicators) {
        this.indicators = indicators == null
                ? Collections.<HealthIndicator>emptyList()
                : Collections.unmodifiableList(new ArrayList<HealthIndicator>(indicators));
    }

    /**
     * 执行一次全量检查。
     *
     * @return 健康报告，保证非 {@code null}
     */
    public HealthReport check() {
        List<HealthResult> results = new ArrayList<HealthResult>(indicators.size());
        for (HealthIndicator indicator : indicators) {
            results.add(checkOne(indicator));
        }
        return new HealthReport(results);
    }

    /**
     * 执行单项检查，抛错与返回 {@code null} 都收敛成一条 {@code DOWN}。
     *
     * @param indicator 检查项
     * @return 检查结果，保证非 {@code null}
     */
    private static HealthResult checkOne(HealthIndicator indicator) {
        Objects.requireNonNull(indicator, "health indicator must not be null");
        try {
            HealthResult result = indicator.check();
            if (result == null) {
                return new HealthResult(indicator.getClass().getSimpleName(), HealthLevel.DOWN, "检查未返回结果");
            }
            return result;
        } catch (RuntimeException e) {
            return new HealthResult(indicator.getClass().getSimpleName(), HealthLevel.DOWN,
                    "检查失败：" + e.getMessage());
        }
    }
}
