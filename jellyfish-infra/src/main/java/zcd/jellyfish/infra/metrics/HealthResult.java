package zcd.jellyfish.infra.metrics;

import java.util.Objects;

import zcd.jellyfish.api.JellyfishException;

/**
 * 单项健康检查结果：检查项名称 + 档位 + 说明，不可变。
 *
 * @author zcd
 */
public final class HealthResult {

    /** 检查项名称（如 {@code model}、{@code plugin}）。 */
    private final String name;

    /** 档位。 */
    private final HealthLevel level;

    /** 说明，可为 {@code null}。 */
    private final String detail;

    /**
     * 构造结果。
     *
     * @param name   检查项名称，不可为空白
     * @param level  档位，不可为 {@code null}
     * @param detail 说明，可为 {@code null}
     */
    public HealthResult(String name, HealthLevel level, String detail) {
        if (name == null || name.trim().isEmpty()) {
            throw new JellyfishException("health name must not be blank");
        }
        this.name = name;
        this.level = Objects.requireNonNull(level, "level must not be null");
        this.detail = detail;
    }

    /**
     * 获取检查项名称。
     *
     * @return 检查项名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取档位。
     *
     * @return 档位
     */
    public HealthLevel getLevel() {
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

    @Override
    public String toString() {
        return name + "=" + level + (detail == null ? "" : "(" + detail + ")");
    }
}
