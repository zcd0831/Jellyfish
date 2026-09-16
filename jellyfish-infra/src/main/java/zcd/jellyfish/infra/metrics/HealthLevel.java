package zcd.jellyfish.infra.metrics;

/**
 * 健康档位。
 * <p>
 * 三档而不是两档：{@code WARN} 表达「当前不可用但不影响说话」的降级（如一个插件都没装），
 * 如果硬塞进 {@code DOWN}，健康报告会在正常配置下天天报警，很快没人再看。
 *
 * @author zcd
 */
public enum HealthLevel {

    /** 正常。 */
    UP,

    /** 降级：功能受限但内核可用。 */
    WARN,

    /** 不可用。 */
    DOWN
}
