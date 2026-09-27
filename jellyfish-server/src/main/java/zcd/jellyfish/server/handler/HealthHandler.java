package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.server.dto.HealthDto;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

/**
 * {@code GET /health} 的处理器。
 * <p>
 * <b>为什么这是唯一保证「永远能答」的接口</b>：它不依赖会话、不依赖模型配置、不依赖插件——
 * 服务化之后，「进程还活着、依赖还正常吗」必须有一个不需要任何前置条件的查询面。
 * <p>
 * <b>为什么状态码恒为 200</b>：报告本身是一次成功的查询，健康与否在 {@code status} 字段里
 * （{@code UP} / {@code WARN} / {@code DOWN}）。用 5xx 表达「有依赖不健康」会让通用探针把
 * 「可观测性降级」误判成「服务挂了」。健康检查项自身抛错也已由 {@code HealthCheck} 收敛成
 * {@code DOWN}，不会冒到这一层。
 * <p>
 * 无状态（只持有健康检查汇总），可安全跨线程调用。
 *
 * @author zcd
 */
public final class HealthHandler {

    /** 健康检查汇总。 */
    private final HealthCheck healthCheck;

    /**
     * 构造处理器。
     *
     * @param healthCheck 健康检查汇总，不可为 {@code null}
     */
    public HealthHandler(HealthCheck healthCheck) {
        this.healthCheck = healthCheck;
    }

    /**
     * 处理一次健康检查查询。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（本端点不使用）
     */
    public void handle(HttpServerExchange exchange, PathParams params) {
        Responses.writeJson(exchange, Responses.OK, HealthDto.of(healthCheck.check()));
    }
}
