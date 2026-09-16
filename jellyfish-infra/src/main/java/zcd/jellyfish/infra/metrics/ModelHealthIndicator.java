package zcd.jellyfish.infra.metrics;

import java.util.Objects;

import zcd.jellyfish.infra.model.ModelManager;

/**
 * 模型健康检查：没有任何 provider 时判为不可用。
 * <p>
 * 这一项是「内核还能不能说话」的最硬指标——provider 为空时任何模型解析都会抛错，
 * 因此它必须能报出来，而不是等到用户敲下第一条消息才发现。
 *
 * @author zcd
 */
public final class ModelHealthIndicator implements HealthIndicator {

    /** 模型门面。 */
    private final ModelManager modelManager;

    /**
     * 构造检查项。
     *
     * @param modelManager 模型门面，不可为 {@code null}
     */
    public ModelHealthIndicator(ModelManager modelManager) {
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
    }

    @Override
    public HealthResult check() {
        int providers = modelManager.getProviders().size();
        if (providers == 0) {
            return new HealthResult("model", HealthLevel.DOWN, "未配置任何 provider");
        }
        return new HealthResult("model", HealthLevel.UP, "providers=" + providers);
    }
}
