package zcd.jellyfish.api.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结果整形裁定：插件在 {@link ToolResultPostRequest} 上能表达的结果。
 * <p>
 * <b>与参数改写的裁定同形，但少一态</b>：这里<b>没有 {@code DENY}</b>。工具已经跑完、副作用已经发生，
 * 此时「拒绝」没有意义——能拒绝的位置是执行前的参数改写与权限判定，两者都在前面。
 * 事后再拦只会让结果凭空消失，而模型无从知道发生了什么。
 * <p>
 * <b>「不表态」与「这一项不改」是两个不同层次的缺省</b>：前者是整条裁定
 * （{@link #abstain()}），后者是替换裁定里把某一项留空。两者的最终效果可能一样，
 * 但区分开来插件才能写「只改元数据、正文原样」（{@link #metadataOnly(Map)}）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolResultAdjustment {

    /** 单例：不表态。 */
    private static final ToolResultAdjustment ABSTAIN = new ToolResultAdjustment(false, null, null);

    /** 是否要改。 */
    private final boolean replace;

    /** 替换后的输出，{@code null} 表示这一项不改。 */
    private final Object output;

    /** 替换后的元数据，{@code null} 表示这一项不改。 */
    private final Map<String, Object> metadata;

    /**
     * 构造裁定。
     * <p>
     * 元数据做了防御性拷贝：插件在返回之后再改自己那份映射，不会影响已经交出去的裁定。
     *
     * @param replace  是否要改
     * @param output   替换后的输出，可为 {@code null}（表示这一项不改）
     * @param metadata 替换后的元数据，可为 {@code null}（表示这一项不改）
     */
    public ToolResultAdjustment(boolean replace, Object output, Map<String, Object> metadata) {
        this.replace = replace;
        this.output = output;
        this.metadata = metadata == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    /**
     * 构造「不表态」裁定。
     *
     * @return 不表态裁定
     */
    public static ToolResultAdjustment abstain() {
        return ABSTAIN;
    }

    /**
     * 构造替换裁定。
     * <p>
     * <b>两个参数为 {@code null} 表示「这一项不改」</b>，因此本方法<b>无法把输出改成 {@code null}</b>；
     * 需要表达「空输出」请传空串或空对象。刻意如此：用 {@code null} 同时表示「不改」与「改成空」
     * 会让调用点的判断变得无法解释。
     * <p>
     * <b>输出保持原始类型</b>：字符串保持字符串、结构化对象保持对象，<b>不得在这里序列化成文本</b>——
     * 截断要知道「这是字符串还是结构化对象」才能选对算法（见 {@code ToolOutputLimiter}）。
     *
     * @param output   替换后的输出，可为 {@code null}（表示这一项不改）
     * @param metadata 替换后的元数据，可为 {@code null}（表示这一项不改）
     * @return 替换裁定
     */
    public static ToolResultAdjustment of(Object output, Map<String, Object> metadata) {
        return new ToolResultAdjustment(true, output, metadata);
    }

    /**
     * 构造「只改元数据」的替换裁定。
     *
     * @param metadata 替换后的元数据，可为 {@code null}（表示这一项不改）
     * @return 替换裁定
     */
    public static ToolResultAdjustment metadataOnly(Map<String, Object> metadata) {
        return new ToolResultAdjustment(true, null, metadata);
    }

    /**
     * 判断是否不表态。
     *
     * @return 不表态返回 {@code true}
     */
    public boolean isAbstain() {
        return !replace;
    }

    /**
     * 判断是否要替换。
     *
     * @return 要替换返回 {@code true}
     */
    public boolean isReplace() {
        return replace;
    }

    /**
     * 判断本次裁定是否要改输出。
     *
     * @return 要改输出返回 {@code true}
     */
    public boolean hasOutput() {
        return replace && output != null;
    }

    /**
     * 判断本次裁定是否要改元数据。
     *
     * @return 要改元数据返回 {@code true}
     */
    public boolean hasMetadata() {
        return replace && metadata != null;
    }

    /**
     * 获取替换后的输出。
     *
     * @return 替换后的输出，可能为 {@code null}（表示这一项不改）
     */
    public Object getOutput() {
        return output;
    }

    /**
     * 获取替换后的元数据。
     *
     * @return 只读元数据映射，可能为 {@code null}（表示这一项不改）
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ToolResultAdjustment{replace=" + replace + ", hasOutput=" + hasOutput()
                + ", hasMetadata=" + hasMetadata() + '}';
    }
}
