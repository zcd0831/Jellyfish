package zcd.jellyfish.infra.event.callback;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.ExtensionPoint;
import zcd.jellyfish.api.event.callback.ExtensionShape;
import zcd.jellyfish.api.event.callback.PluginExtensible;

import java.util.Objects;

/**
 * 扩展点定义：形状预设与 {@link ExtensionPoint} 覆盖合并后的不可变结果。
 * <p>
 * 定义是回调通道的调用语义来源：唯一性、顺序、空表策略、结果数量、执行模式与失败语义全部取自这里，
 * 而不是像改造前那样按类型硬编码。定义在启动期解析并校验，调用点不得临时改变。
 *
 * @author zcd
 */
public final class ExtensionPointDefinition {

    /** 扩展点稳定标识。 */
    private final String id;

    /** 形状预设。 */
    private final ExtensionShape shape;

    /** 承载本定义的回调类型。 */
    private final Class<? extends Callback<?>> callbackType;

    /** 是否只允许一个处理器。 */
    private final boolean unique;

    /** 是否有顺序语义。 */
    private final boolean ordered;

    /** 空表策略。 */
    private final ExtensionPoint.EmptyPolicy emptyPolicy;

    /** 结果数量。 */
    private final ExtensionPoint.ResultArity resultArity;

    /** 执行模式。 */
    private final ExtensionPoint.Execution execution;

    /** 失败语义。 */
    private final ExtensionPoint.FailurePolicy failurePolicy;

    /** 是否允许插件注册。 */
    private final boolean pluginExtensible;

    /**
     * 构造定义。
     *
     * @param id               扩展点标识
     * @param shape            形状
     * @param callbackType     回调类型
     * @param unique           是否唯一
     * @param ordered          是否有顺序语义
     * @param emptyPolicy      空表策略
     * @param resultArity      结果数量
     * @param execution        执行模式
     * @param failurePolicy    失败语义
     * @param pluginExtensible 是否允许插件注册
     */
    private ExtensionPointDefinition(String id, ExtensionShape shape, Class<? extends Callback<?>> callbackType,
                                     boolean unique, boolean ordered, ExtensionPoint.EmptyPolicy emptyPolicy,
                                     ExtensionPoint.ResultArity resultArity, ExtensionPoint.Execution execution,
                                     ExtensionPoint.FailurePolicy failurePolicy, boolean pluginExtensible) {
        this.id = id;
        this.shape = shape;
        this.callbackType = callbackType;
        this.unique = unique;
        this.ordered = ordered;
        this.emptyPolicy = emptyPolicy;
        this.resultArity = resultArity;
        this.execution = execution;
        this.failurePolicy = failurePolicy;
        this.pluginExtensible = pluginExtensible;
    }

    /**
     * 从回调类型上的 {@link ExtensionPoint} 解析定义。
     *
     * @param callbackType 回调类型，不可为 {@code null}
     * @return 解析后的定义
     * @throws JellyfishException 回调类型未标注 {@link ExtensionPoint}，或标识为空时抛出
     */
    public static ExtensionPointDefinition resolve(Class<? extends Callback<?>> callbackType) {
        Objects.requireNonNull(callbackType, "callbackType must not be null");
        ExtensionPoint annotation = callbackType.getAnnotation(ExtensionPoint.class);
        if (annotation == null) {
            throw new JellyfishException("callback type is not an extension point: " + callbackType.getName());
        }
        if (annotation.id() == null || annotation.id().trim().isEmpty()) {
            throw new JellyfishException("extension point id must not be blank: " + callbackType.getName());
        }
        ExtensionShape shape = annotation.shape();
        return new ExtensionPointDefinition(
                annotation.id(),
                shape,
                callbackType,
                resolve(annotation.unique(), shape.isUnique()),
                resolve(annotation.ordered(), shape.isOrdered()),
                annotation.emptyPolicy() == ExtensionPoint.EmptyPolicy.INHERIT
                        ? shape.getEmptyPolicy() : annotation.emptyPolicy(),
                annotation.resultArity() == ExtensionPoint.ResultArity.INHERIT
                        ? shape.getResultArity() : annotation.resultArity(),
                annotation.execution() == ExtensionPoint.Execution.INHERIT
                        ? shape.getExecution() : annotation.execution(),
                annotation.failurePolicy() == ExtensionPoint.FailurePolicy.INHERIT
                        ? shape.getFailurePolicy() : annotation.failurePolicy(),
                callbackType.isAnnotationPresent(PluginExtensible.class));
    }

    /**
     * 合并三态覆盖与形状默认值。
     *
     * @param override        覆盖值
     * @param shapeDefault    形状默认值
     * @return 合并后的布尔值
     */
    private static boolean resolve(ExtensionPoint.TriState override, boolean shapeDefault) {
        if (override == ExtensionPoint.TriState.INHERIT) {
            return shapeDefault;
        }
        return override == ExtensionPoint.TriState.TRUE;
    }

    /**
     * 获取扩展点标识。
     *
     * @return 扩展点标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取形状。
     *
     * @return 形状
     */
    public ExtensionShape getShape() {
        return shape;
    }

    /**
     * 获取承载本定义的回调类型。
     *
     * @return 回调类型
     */
    public Class<? extends Callback<?>> getCallbackType() {
        return callbackType;
    }

    /**
     * 判断是否只允许一个处理器。
     *
     * @return 唯一返回 {@code true}
     */
    public boolean isUnique() {
        return unique;
    }

    /**
     * 判断是否有顺序语义。
     *
     * @return 有序返回 {@code true}
     */
    public boolean isOrdered() {
        return ordered;
    }

    /**
     * 获取空表策略。
     *
     * @return 空表策略
     */
    public ExtensionPoint.EmptyPolicy getEmptyPolicy() {
        return emptyPolicy;
    }

    /**
     * 获取结果数量。
     *
     * @return 结果数量
     */
    public ExtensionPoint.ResultArity getResultArity() {
        return resultArity;
    }

    /**
     * 获取执行模式。
     *
     * @return 执行模式
     */
    public ExtensionPoint.Execution getExecution() {
        return execution;
    }

    /**
     * 获取失败语义。
     *
     * @return 失败语义
     */
    public ExtensionPoint.FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    /**
     * 判断是否允许插件注册。
     *
     * @return 允许返回 {@code true}
     */
    public boolean isPluginExtensible() {
        return pluginExtensible;
    }

    @Override
    public String toString() {
        return "ExtensionPointDefinition{id=" + id + ", shape=" + shape + ", unique=" + unique
                + ", ordered=" + ordered + ", emptyPolicy=" + emptyPolicy + ", resultArity=" + resultArity
                + ", execution=" + execution + ", failurePolicy=" + failurePolicy
                + ", pluginExtensible=" + pluginExtensible + '}';
    }
}
