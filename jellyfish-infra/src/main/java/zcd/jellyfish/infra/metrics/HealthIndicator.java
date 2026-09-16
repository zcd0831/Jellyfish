package zcd.jellyfish.infra.metrics;

/**
 * 健康检查项：一个可独立判定与失败隔离的观察点。
 * <p>
 * 抽成接口而不是「一个大方法查所有东西」，是为了让不同模块各自贡献自己那一项，
 * 且单项实现只能依赖自己那一层的协作者——{@code infra} 的检查项不该为了报告 compactor 的可用性
 * 去依赖 {@code core}。
 *
 * @author zcd
 */
@FunctionalInterface
public interface HealthIndicator {

    /**
     * 执行一次检查。
     * <p>
     * 实现应<b>快而且只读</b>：它会被诊断入口随时调用。实现抛错由 {@link HealthCheck} 统一收敛，
     * 不需要自己兜底，但也不应依赖异常来表达「不健康」——那属于 {@link HealthLevel#DOWN}。
     *
     * @return 检查结果，不可为 {@code null}
     */
    HealthResult check();
}
