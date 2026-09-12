package zcd.jellyfish.api.event;

/**
 * 通知事件标记接口。
 * <p>
 * 通知是广播语义（0..N 个订阅者），不携带返回值；实现类的字段应全部只读。
 *
 * @author zcd
 */
public interface JellyfishEvent {

    /**
     * 获取事件唯一标识，用于日志关联与去重排查。
     *
     * @return 事件唯一标识
     */
    String getEventId();

    /**
     * 获取事件发生时间戳（毫秒）。
     *
     * @return 时间戳
     */
    long getOccurredAt();

    /**
     * 获取事件所属会话标识。
     * <p>
     * 事件总线没有定向派发能力，多会话并发时订阅方据此自判归属，因此会话标识放在事件对象上，
     * 而不是依赖调用线程的 ThreadLocal。
     *
     * @return 会话标识；进程级事件返回 {@code null}
     */
    String getSessionId();
}
