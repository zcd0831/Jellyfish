package zcd.jellyfish.infra.metrics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HealthCheck} 与 {@link HealthReport} 的单元测试：单项失败隔离与整体判定。
 *
 * @author zcd
 */
class HealthCheckTest {

    @Test
    void check_should_collect_results_in_registration_order() {
        // Given
        List<HealthIndicator> indicators = Arrays.<HealthIndicator>asList(
                () -> new HealthResult("first", HealthLevel.UP, "ok"),
                () -> new HealthResult("second", HealthLevel.WARN, "降级"));

        // When
        HealthReport report = new HealthCheck(indicators).check();

        // Then
        assertEquals(2, report.getResults().size());
        assertEquals("first", report.getResults().get(0).getName());
        assertEquals("second", report.getResults().get(1).getName());
    }

    @Test
    void check_should_isolate_indicator_failure_as_down() {
        // Given：坏检查项只把自己变成 DOWN，不影响其它项
        List<HealthIndicator> indicators = Arrays.<HealthIndicator>asList(
                () -> {
                    throw new IllegalStateException("炸了");
                },
                () -> new HealthResult("good", HealthLevel.UP, "ok"));

        // When
        HealthReport report = new HealthCheck(indicators).check();

        // Then
        assertEquals(2, report.getResults().size());
        assertEquals(HealthLevel.DOWN, report.getResults().get(0).getLevel());
        assertTrue(report.getResults().get(0).getDetail().contains("炸了"));
        assertEquals(HealthLevel.UP, report.getResults().get(1).getLevel());
    }

    @Test
    void check_should_treat_null_result_as_down() {
        // Given
        HealthCheck check = new HealthCheck(Collections.<HealthIndicator>singletonList(() -> null));

        // When / Then
        assertEquals(HealthLevel.DOWN, check.check().getResults().get(0).getLevel());
    }

    @Test
    void report_should_be_healthy_when_nothing_is_down() {
        // Given：WARN 不算不健康——「插件没装」是合法配置
        HealthCheck check = new HealthCheck(Arrays.<HealthIndicator>asList(
                () -> new HealthResult("a", HealthLevel.UP, null),
                () -> new HealthResult("b", HealthLevel.WARN, null)));

        // When
        HealthReport report = check.check();

        // Then
        assertTrue(report.isHealthy());
    }

    @Test
    void report_should_be_unhealthy_when_any_item_is_down() {
        // Given
        HealthCheck check = new HealthCheck(Arrays.<HealthIndicator>asList(
                () -> new HealthResult("a", HealthLevel.UP, null),
                () -> new HealthResult("b", HealthLevel.DOWN, "挂了")));

        // When
        HealthReport report = check.check();

        // Then
        assertFalse(report.isHealthy());
        assertTrue(report.render().contains("b=DOWN(挂了)"));
    }

    @Test
    void check_should_handle_no_indicator() {
        // When
        HealthReport report = new HealthCheck(null).check();

        // Then
        assertTrue(report.getResults().isEmpty());
        assertTrue(report.isHealthy());
        assertEquals("（无健康检查项）", report.render());
    }

    @Test
    void result_should_reject_blank_name() {
        // When / Then
        org.junit.jupiter.api.Assertions.assertThrows(zcd.jellyfish.api.JellyfishException.class,
                () -> new HealthResult(" ", HealthLevel.UP, null));
    }
}
