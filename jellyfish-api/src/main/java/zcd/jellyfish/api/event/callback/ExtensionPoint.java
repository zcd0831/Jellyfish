package zcd.jellyfish.api.event.callback;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 扩展点声明：标注在回调类型上，声明它属于哪个扩展点、采用哪个形状。
 * <p>
 * 形状（{@link ExtensionShape}）给出一组固定的参数预设；扩展点可用本注解的具名成员逐项覆盖，
 * 未覆盖时取形状默认值（{@code INHERIT}）。覆盖必须声明在回调类型上，<b>不允许在调用点临时改变</b>。
 * <p>
 * 这些参数属于内核的调用语义，<b>不向插件暴露</b>：插件只按扩展点注册处理器、只声明 {@code order}。
 *
 * @author zcd
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExtensionPoint {

    /**
     * 扩展点稳定标识，用于 {@code plugin.properties} 声明与权限校验。
     *
     * @return 扩展点 ID，如 {@code tool.provide}
     */
    String id();

    /**
     * 形状预设。
     *
     * @return 形状
     */
    ExtensionShape shape();

    /**
     * 覆盖「是否唯一」。
     *
     * @return 覆盖值，默认继承形状
     */
    TriState unique() default TriState.INHERIT;

    /**
     * 覆盖「是否有序」。
     *
     * @return 覆盖值，默认继承形状
     */
    TriState ordered() default TriState.INHERIT;

    /**
     * 覆盖空表策略。
     *
     * @return 覆盖值，默认继承形状
     */
    EmptyPolicy emptyPolicy() default EmptyPolicy.INHERIT;

    /**
     * 覆盖结果数量。
     *
     * @return 覆盖值，默认继承形状
     */
    ResultArity resultArity() default ResultArity.INHERIT;

    /**
     * 覆盖执行模式。
     *
     * @return 覆盖值，默认继承形状
     */
    Execution execution() default Execution.INHERIT;

    /**
     * 覆盖失败语义。
     *
     * @return 覆盖值，默认继承形状
     */
    FailurePolicy failurePolicy() default FailurePolicy.INHERIT;

    /** 三态开关：用于覆盖布尔型形状参数。 */
    enum TriState {

        /** 继承形状默认值。 */
        INHERIT,

        /** 覆盖为真。 */
        TRUE,

        /** 覆盖为假。 */
        FALSE
    }

    /** 空表策略：无处理器时硬失败，还是视为无贡献继续。 */
    enum EmptyPolicy {

        /** 继承形状默认值。 */
        INHERIT,

        /** 无处理器即硬失败（{@code NO_HANDLER}），调用方依赖结果。 */
        REQUIRED,

        /** 无处理器视为无贡献 / 直通 / 不额外拒绝。 */
        OPTIONAL
    }

    /** 结果数量：调用方要单个结果还是全部结果。 */
    enum ResultArity {

        /** 继承形状默认值。 */
        INHERIT,

        /** 单个结果，等价于原回调通道行为。 */
        ONE,

        /** 全部成功结果，按处理器调用顺序排列。 */
        MANY
    }

    /** 执行模式：调用线程内联，还是提交到专用线程池并按处理器施加超时。 */
    enum Execution {

        /** 继承形状默认值。 */
        INHERIT,

        /** 调用线程内联执行，超时只能由处理器自律。 */
        INLINE,

        /** 专用线程池按序逐个调用，内核可强制超时。 */
        ISOLATED
    }

    /** 失败语义：单个处理器失败时丢弃该结果，还是让整次调用失败。 */
    enum FailurePolicy {

        /** 继承形状默认值。 */
        INHERIT,

        /** 丢弃失败处理器（超时 / 异常）的结果并计数，不阻塞其余处理器。 */
        FAIL_OPEN,

        /** 任一处理器失败即让整次调用失败。 */
        FAIL_CLOSED
    }
}
