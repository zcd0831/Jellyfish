package zcd.jellyfish.server.dto;

import zcd.jellyfish.infra.metrics.HealthLevel;
import zcd.jellyfish.infra.metrics.HealthResult;

/**
 * 单项健康检查结果：{@code GET /health} 的 {@code results} 元素。
 * <p>
 * {@code level} 下发枚举名（{@code UP} / {@code WARN} / {@code DOWN}），前端按字面量分支。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class HealthItemDto {

    /** 检查项名。 */
    private final String name;

    /** 检查结果档位字面量。 */
    private final String level;

    /** 说明文本，可为 {@code null}。 */
    private final String detail;

    /**
     * 构造检查项。
     *
     * @param name   检查项名
     * @param level  档位字面量
     * @param detail 说明，可为 {@code null}
     */
    public HealthItemDto(String name, String level, String detail) {
        this.name = name;
        this.level = level;
        this.detail = detail;
    }

    /**
     * 把内核检查结果投影成 DTO。
     *
     * @param result 内核检查结果，不可为 {@code null}
     * @return 检查项 DTO
     */
    public static HealthItemDto of(HealthResult result) {
        HealthLevel level = result.getLevel();
        return new HealthItemDto(result.getName(), level == null ? null : level.name(), result.getDetail());
    }

    /**
     * 获取检查项名。
     *
     * @return 检查项名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取档位字面量。
     *
     * @return 档位字面量，可能为 {@code null}
     */
    public String getLevel() {
        return level;
    }

    /**
     * 获取说明。
     *
     * @return 说明，可能为 {@code null}
     */
    public String getDetail() {
        return detail;
    }
}
