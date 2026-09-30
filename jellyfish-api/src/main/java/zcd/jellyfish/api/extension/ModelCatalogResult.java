package zcd.jellyfish.api.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型目录结果：插件交回「这个 provider 现在有哪些模型」。
 * <p>
 * <b>空列表的含义是「我不表态」，不是「一个模型都没有」</b>：前者回落成配置里 {@code models} 写的那份，
 * 后者会把一个本来能用的 provider 清空。两者无法区分时，内核按前者处理——这是安全的那一侧：
 * 发现失败最坏的结果是「用回配置里的列表」，而不是「provider 突然没有模型可用了」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ModelCatalogResult {

    /** 发现到的模型。 */
    private final List<ModelDescriptor> models;

    /**
     * 构造结果。
     *
     * @param models 发现到的模型，可为 {@code null}
     */
    public ModelCatalogResult(List<ModelDescriptor> models) {
        this.models = models == null
                ? Collections.<ModelDescriptor>emptyList()
                : Collections.unmodifiableList(new ArrayList<ModelDescriptor>(models));
    }

    /**
     * 构造「不表态」的空结果。
     *
     * @return 空结果
     */
    public static ModelCatalogResult empty() {
        return new ModelCatalogResult(null);
    }

    /**
     * 构造带模型列表的结果。
     *
     * @param models 发现到的模型，可为 {@code null}（等价 {@link #empty()}）
     * @return 结果
     */
    public static ModelCatalogResult of(List<ModelDescriptor> models) {
        return new ModelCatalogResult(models);
    }

    /**
     * 获取发现到的模型。
     *
     * @return 模型列表，可能为空但不会为 {@code null}
     */
    public List<ModelDescriptor> getModels() {
        return models;
    }

    /**
     * 判断插件是否表了态。
     *
     * @return 有模型时返回 {@code true}
     */
    public boolean isPresent() {
        return !models.isEmpty();
    }

    @Override
    public String toString() {
        return "ModelCatalogResult{models=" + models.size() + '}';
    }
}
