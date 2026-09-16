package zcd.jellyfish.infra.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 健康报告：一次检查的全部结果，不可变。
 *
 * @author zcd
 */
public final class HealthReport {

    /** 检查结果，保持注册顺序。 */
    private final List<HealthResult> results;

    /**
     * 构造报告。
     *
     * @param results 检查结果，可为 {@code null}
     */
    HealthReport(List<HealthResult> results) {
        this.results = results == null
                ? Collections.<HealthResult>emptyList()
                : Collections.unmodifiableList(new ArrayList<HealthResult>(results));
    }

    /**
     * 获取全部检查结果。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public List<HealthResult> getResults() {
        return results;
    }

    /**
     * 判断整体是否健康：没有任何一项处于 {@link HealthLevel#DOWN}。
     * <p>
     * {@code WARN} 不算不健康——「插件没装」是合法配置，不该让整体判定为故障。
     *
     * @return 无 {@code DOWN} 项返回 {@code true}
     */
    public boolean isHealthy() {
        for (HealthResult result : results) {
            if (result.getLevel() == HealthLevel.DOWN) {
                return false;
            }
        }
        return true;
    }

    /**
     * 渲染为单行可读文本。
     *
     * @return 可读文本；无检查项时为一句说明
     */
    public String render() {
        if (results.isEmpty()) {
            return "（无健康检查项）";
        }
        StringBuilder text = new StringBuilder();
        for (HealthResult result : results) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(result);
        }
        return text.toString();
    }
}
