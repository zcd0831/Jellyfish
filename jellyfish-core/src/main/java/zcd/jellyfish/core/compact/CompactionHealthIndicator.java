package zcd.jellyfish.core.compact;

import java.util.Objects;

import zcd.jellyfish.infra.metrics.HealthIndicator;
import zcd.jellyfish.infra.metrics.HealthLevel;
import zcd.jellyfish.infra.metrics.HealthResult;

/**
 * 会话压缩健康检查：没有插件提供压缩策略时判为降级。
 * <p>
 * <b>为什么由 core 贡献这一项</b>：压缩是否可用只有 {@link ConversationCompactor} 知道，
 * 而它在 {@code core}；{@code infra/metrics} 的健康检查项只能依赖 {@code infra} 的协作者，
 * 否则基础层就要反向依赖应用层。因此检查项实现放在这里，由装配根注入 {@code HealthCheck}。
 * <p>
 * 判为 {@code WARN} 而非 {@code DOWN}：没有压缩插件是合法配置，长会话会因为机械裁剪
 * 丢历史、信息有损失，但对话本身完全可用。
 *
 * @author zcd
 */
public final class CompactionHealthIndicator implements HealthIndicator {

    /** 检查项名称。 */
    private static final String NAME = "compaction";

    /** 会话压缩器。 */
    private final ConversationCompactor compactor;

    /**
     * 构造检查项。
     *
     * @param compactor 会话压缩器，不可为 {@code null}
     */
    public CompactionHealthIndicator(ConversationCompactor compactor) {
        this.compactor = Objects.requireNonNull(compactor, "compactor must not be null");
    }

    @Override
    public HealthResult check() {
        if (!compactor.isAvailable()) {
            return new HealthResult(NAME, HealthLevel.WARN, "没有插件提供压缩策略");
        }
        return new HealthResult(NAME, HealthLevel.UP, "可用");
    }
}
