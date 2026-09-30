package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 插件目录里的一个模型。
 * <p>
 * <b>它为什么重复了 {@code models.json} 的字段</b>：那是 {@code infra} 侧的配置类型，插件看不到。
 * 两者字段一致是刻意的——发现结果会被内核直接换成配置类型，多一个字段就多一处要维护的映射。
 * <p>
 * <b>{@code contextLength} 与 {@code maxOutputTokens} 允许为 0</b>，含义是「不知道」。内核对此的口径
 * 已经统一：<b>上下文窗口未知就不按窗口裁剪历史</b>（宁可让厂商自己报错，也好过内核猜一个数把历史丢掉），
 * 输出上限未知就不下发该字段。因此插件不必为了填这两个数去猜一个「看起来合理」的值——
 * 猜错会让内核提前把历史丢掉，那比报错更难查。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ModelDescriptor {

    /** 模型标识，调用厂商接口时使用的 {@code model} 字段。 */
    private final String id;

    /** 模型展示名，用户在选择模型时看到的名称；与 {@code id} 不同时说明端点用了别名。 */
    private final String name;

    /** 上下文窗口长度（token），0 表示未知。 */
    private final int contextLength;

    /** 单次最大输出 token 数，0 表示未知。 */
    private final int maxOutputTokens;

    /**
     * 构造模型描述。
     *
     * @param id              模型标识，不可为空白
     * @param name            展示名，为 {@code null} 时取 {@code id}
     * @param contextLength   上下文窗口长度（token），0 表示未知
     * @param maxOutputTokens 单次最大输出 token 数，0 表示未知
     * @throws JellyfishException 模型标识为空白时抛出
     */
    public ModelDescriptor(String id, String name, int contextLength, int maxOutputTokens) {
        if (id == null || id.trim().isEmpty()) {
            throw new JellyfishException("model descriptor id must not be blank");
        }
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? id : name;
        this.contextLength = Math.max(0, contextLength);
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    /**
     * 构造只知标识的模型描述，规格按「未知」处理。
     *
     * @param id 模型标识
     * @return 模型描述
     */
    public static ModelDescriptor of(String id) {
        return new ModelDescriptor(id, null, 0, 0);
    }

    /**
     * 获取模型标识。
     *
     * @return 模型标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取展示名。
     *
     * @return 展示名，保证非空白
     */
    public String getName() {
        return name;
    }

    /**
     * 获取上下文窗口长度。
     *
     * @return 上下文窗口长度（token），0 表示未知
     */
    public int getContextLength() {
        return contextLength;
    }

    /**
     * 获取单次最大输出 token 数。
     *
     * @return 单次最大输出 token 数，0 表示未知
     */
    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    @Override
    public String toString() {
        return "ModelDescriptor{id=" + id + ", name=" + name
                + ", contextLength=" + contextLength + '}';
    }
}
