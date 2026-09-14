package zcd.jellyfish.infra.plugin;

import org.pf4j.DefaultVersionManager;
import org.pf4j.VersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 防御式版本管理器：包装 {@link DefaultVersionManager}，把版本表达式解析异常降级为「不满足」。
 * <p>
 * 存在的理由是一条实测结论：PF4J 的 {@code isPluginValid} 会把 {@code plugin.requires} 交给版本管理器，
 * 而非法表达式（例如含 {@code -SNAPSHOT}）会让 semver 解析抛运行时异常；{@code loadPlugins()} 只捕获
 * {@code PluginRuntimeException}，于是「一个插件的坏表达式」会中断整批加载。
 * <p>
 * 与「把 {@code loadPlugins()} 整体包一层」相比，本类才是正确的解法：外层 catch 只能在第一个坏插件处
 * 截断循环，比 PF4J 原有行为更差；而本类把异常降级为「这一个插件不满足」，与 PF4J 自身的逐插件语义对齐。
 *
 * @author zcd
 */
public final class DefensiveVersionManager implements VersionManager {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(DefensiveVersionManager.class);

    /** 探测用基准版本：只为触发表达式解析，取值不影响合法性判定。 */
    private static final String PROBE_VERSION = "0.0.0";

    /** 通配约束，表示不限版本。 */
    private static final String ANY_CONSTRAINT = "*";

    /** 被包装的版本管理器。 */
    private final VersionManager delegate;

    /**
     * 以 PF4J 默认版本管理器构造。
     */
    public DefensiveVersionManager() {
        this(new DefaultVersionManager());
    }

    /**
     * 以指定版本管理器构造，便于测试替换。
     *
     * @param delegate 被包装的版本管理器，不可为 {@code null}
     */
    DefensiveVersionManager(VersionManager delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public boolean checkVersionConstraint(String version, String constraint) {
        try {
            return delegate.checkVersionConstraint(version, constraint);
        } catch (RuntimeException e) {
            LOG.warn("版本约束解析失败，按不满足处理: version={} constraint={}", version, constraint, e);
            return false;
        }
    }

    @Override
    public int compareVersions(String version1, String version2) {
        // 刻意不做 catch：PF4J 3.14 内部不会调用本方法（已核对字节码），
        // 若将来被调用，暴露错误也比返回 0（被理解为「相等」）更安全——
        // 后者会把不匹配的依赖静默判定为匹配。
        return delegate.compareVersions(version1, version2);
    }

    /**
     * 判断版本约束表达式是否合法。
     * <p>
     * 用一个固定基准版本去解析表达式：合法表达式只会返回布尔值，非法表达式才会抛异常，
     * 因此可以借它区分「不满足」与「写错了」。
     *
     * @param constraint 约束表达式，可为 {@code null} 或空白
     * @return 合法或为空时返回 {@code true}
     */
    public boolean isValidConstraint(String constraint) {
        if (constraint == null || constraint.trim().isEmpty() || ANY_CONSTRAINT.equals(constraint.trim())) {
            return true;
        }
        try {
            delegate.checkVersionConstraint(PROBE_VERSION, constraint.trim());
            return true;
        } catch (RuntimeException e) {
            LOG.warn("版本约束非法: constraint={}", constraint, e);
            return false;
        }
    }
}
