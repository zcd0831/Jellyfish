package zcd.jellyfish.infra.event.notification;

/**
 * 一次通知派发的统计结果。
 * <p>
 * 注册表只负责广播与异常隔离，指标累加交给门面，因此这里只回传计数。
 *
 * @author zcd
 */
public final class EventDispatchResult {

    /** 通过过滤条件并实际被调用的监听器数量。 */
    private final int matched;

    /** 抛异常被隔离的监听器数量。 */
    private final int errors;

    /**
     * 构造派发结果。
     *
     * @param matched 命中的监听器数量
     * @param errors  出错的监听器数量
     */
    EventDispatchResult(int matched, int errors) {
        this.matched = matched;
        this.errors = errors;
    }

    /**
     * 获取命中的监听器数量。
     *
     * @return 命中的监听器数量
     */
    public int getMatched() {
        return matched;
    }

    /**
     * 获取出错的监听器数量。
     *
     * @return 出错的监听器数量
     */
    public int getErrors() {
        return errors;
    }
}
