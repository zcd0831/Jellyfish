package zcd.jellyfish.api.event.callback;

/**
 * 扩展点形状：每个形状一组固定的参数预设（语义层）。
 * <p>
 * A 贡献 / B 提供 / D 拦截 / E 策略；C 观察走事件通道，不产生回调，因此不在本枚举中。
 * 形状只给默认值，回调类型可用 {@link ExtensionPoint} 逐项覆盖。
 *
 * @author zcd
 */
public enum ExtensionShape {

    /** A 贡献：0..N 有返回值，有序，收集全部片段，由调用方聚合。 */
    CONTRIBUTE(false, true, ExtensionPoint.EmptyPolicy.OPTIONAL, ExtensionPoint.ResultArity.MANY,
            ExtensionPoint.Execution.ISOLATED, ExtensionPoint.FailurePolicy.FAIL_OPEN),

    /** B 提供：按路由键分发，恰好一个，无顺序语义。 */
    PROVIDE(true, false, ExtensionPoint.EmptyPolicy.REQUIRED, ExtensionPoint.ResultArity.ONE,
            ExtensionPoint.Execution.INLINE, ExtensionPoint.FailurePolicy.FAIL_CLOSED),

    /** D 拦截：有序链，可改写 / 可短路，结果由调用方链式组合。 */
    INTERCEPT(false, true, ExtensionPoint.EmptyPolicy.OPTIONAL, ExtensionPoint.ResultArity.MANY,
            ExtensionPoint.Execution.ISOLATED, ExtensionPoint.FailurePolicy.FAIL_OPEN),

    /** E 策略：只能收紧，逐个询问，任一 deny 即拒，异常按 fail-closed 处理。 */
    POLICY(false, true, ExtensionPoint.EmptyPolicy.OPTIONAL, ExtensionPoint.ResultArity.MANY,
            ExtensionPoint.Execution.ISOLATED, ExtensionPoint.FailurePolicy.FAIL_CLOSED);

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

    /**
     * 构造形状预设。
     *
     * @param unique        是否只允许一个处理器
     * @param ordered       是否有顺序语义
     * @param emptyPolicy   空表策略
     * @param resultArity   结果数量
     * @param execution     执行模式
     * @param failurePolicy 失败语义
     */
    ExtensionShape(boolean unique, boolean ordered, ExtensionPoint.EmptyPolicy emptyPolicy,
                   ExtensionPoint.ResultArity resultArity, ExtensionPoint.Execution execution,
                   ExtensionPoint.FailurePolicy failurePolicy) {
        this.unique = unique;
        this.ordered = ordered;
        this.emptyPolicy = emptyPolicy;
        this.resultArity = resultArity;
        this.execution = execution;
        this.failurePolicy = failurePolicy;
    }

    /**
     * 获取是否只允许一个处理器。
     *
     * @return 唯一返回 {@code true}
     */
    public boolean isUnique() {
        return unique;
    }

    /**
     * 获取是否有顺序语义。
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
}
