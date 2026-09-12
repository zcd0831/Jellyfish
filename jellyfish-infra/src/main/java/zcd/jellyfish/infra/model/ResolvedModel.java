package zcd.jellyfish.infra.model;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;

import java.util.Objects;

/**
 * 一次模型解析的结果：把「provider 名 + model 名」落到具体的 {@link Provider} 与 {@link Model}。
 * <p>
 * 不可变的值对象，只描述「解析到了哪个模型」，<b>不承担任何选择 / 切换语义</b>。会话持有的
 * 「当前模型」就是本类型的实例；会话切换模型即替换该实例，不会影响其它会话。
 *
 * @author zcd
 */
public final class ResolvedModel {

    /** 命中的 provider。 */
    private final Provider provider;

    /** 命中的 model，隶属于 {@link #provider}。 */
    private final Model model;

    /**
     * 构造解析结果。
     *
     * @param provider 命中的 provider，不可为 {@code null}
     * @param model    命中的 model，不可为 {@code null}
     * @throws JellyfishException provider 或 model 为 {@code null} 时抛出
     */
    public ResolvedModel(Provider provider, Model model) {
        if (provider == null || model == null) {
            throw new JellyfishException("provider and model must not be null");
        }
        this.provider = provider;
        this.model = model;
    }

    /**
     * 获取命中的 provider。
     *
     * @return provider
     */
    public Provider getProvider() {
        return provider;
    }

    /**
     * 获取命中的 model。
     *
     * @return model
     */
    public Model getModel() {
        return model;
    }

    /**
     * 获取命中的 provider 名。
     *
     * @return provider 名
     */
    public String getProviderName() {
        return provider.getName();
    }

    /**
     * 获取命中的 model 名。
     *
     * @return model 名
     */
    public String getModelName() {
        return model.getName();
    }

    /**
     * 以「provider 名 + model 名」判定解析结果是否相同，避免依赖配置对象的引用相等。
     *
     * @param other 待比较对象
     * @return 指向同一 provider 下同一模型时返回 {@code true}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        ResolvedModel that = (ResolvedModel) other;
        return Objects.equals(provider.getName(), that.provider.getName())
                && Objects.equals(model.getName(), that.model.getName());
    }

    /**
     * 与 {@link #equals(Object)} 保持一致的哈希值。
     *
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        return Objects.hash(provider.getName(), model.getName());
    }

    /**
     * 输出便于日志排查的「provider/model」文本。
     *
     * @return 文本形式
     */
    @Override
    public String toString() {
        return provider.getName() + "/" + model.getName();
    }
}
