package zcd.jellyfish.server.dto;

import zcd.jellyfish.infra.metrics.HealthReport;
import zcd.jellyfish.infra.metrics.HealthResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 健康报告：{@code GET /health} 的返回体。
 * <p>
 * <b>为什么要这个端点</b>：服务化之后「这个进程还活着、依赖还正常吗」需要一个不依赖模型的查询面——
 * 它必须能在没有任何会话、没有任何模型配置时回答。因此 {@code /health} 是唯一保证「永远能答」的接口。
 * <p>
 * {@code status} 是三档字面量：只要有 {@code DOWN} 项就是 {@code DOWN}，否则有 {@code WARN} 就是
 * {@code WARN}，全 {@code UP} 才是 {@code UP}。这样运维只需看一个字段，细节再到 {@code results} 里翻。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class HealthDto {

    /** 整体档位：健康。 */
    public static final String STATUS_UP = "UP";

    /** 整体档位：有告警但没有故障。 */
    public static final String STATUS_WARN = "WARN";

    /** 整体档位：存在故障。 */
    public static final String STATUS_DOWN = "DOWN";

    /** 整体档位字面量。 */
    private final String status;

    /** 是否健康（没有任何 {@code DOWN} 项）。 */
    private final boolean healthy;

    /** 各检查项结果，保证非 {@code null}。 */
    private final List<HealthItemDto> results;

    /**
     * 构造健康报告。
     *
     * @param status  整体档位字面量
     * @param healthy 是否健康
     * @param results 检查项结果，可为 {@code null}
     */
    public HealthDto(String status, boolean healthy, List<HealthItemDto> results) {
        this.status = status;
        this.healthy = healthy;
        this.results = results == null
                ? Collections.<HealthItemDto>emptyList()
                : Collections.unmodifiableList(new ArrayList<HealthItemDto>(results));
    }

    /**
     * 把内核健康报告投影成 DTO，并计算整体档位。
     *
     * @param report 内核健康报告，不可为 {@code null}
     * @return 健康报告 DTO
     */
    public static HealthDto of(HealthReport report) {
        List<HealthItemDto> items = new ArrayList<HealthItemDto>();
        boolean warn = false;
        for (HealthResult result : report.getResults()) {
            HealthItemDto item = HealthItemDto.of(result);
            items.add(item);
            if (STATUS_WARN.equals(item.getLevel())) {
                warn = true;
            }
        }
        String status = report.isHealthy() ? (warn ? STATUS_WARN : STATUS_UP) : STATUS_DOWN;
        return new HealthDto(status, report.isHealthy(), items);
    }

    /**
     * 获取整体档位字面量。
     *
     * @return 档位字面量
     */
    public String getStatus() {
        return status;
    }

    /**
     * 判断整体是否健康。
     *
     * @return 无 {@code DOWN} 项返回 {@code true}
     */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * 获取检查项结果。
     *
     * @return 检查项列表，保证非 {@code null}
     */
    public List<HealthItemDto> getResults() {
        return results;
    }
}
