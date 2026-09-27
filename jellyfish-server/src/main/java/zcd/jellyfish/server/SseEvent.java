package zcd.jellyfish.server;

/**
 * 一条待写出的 SSE 事件：事件名 + 载荷 + 是否终态。
 * <p>
 * <b>为什么把「终态」标记放在事件上，而不是让发送线程自己判断类型</b>：终态是「这条事件写完就该关流」的
 * 唯一判据，而它只有产生事件的一方（{@link SseReActListener}）最清楚。放在这里，写循环就只剩
 * 「写一条、看标记」两步，不需要再认识 {@code done} / {@code cancelled} / {@code error} 三个名字。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SseEvent {

    /** 事件名。 */
    private final String name;

    /** 载荷对象。 */
    private final Object payload;

    /** 是否终态（写完即关流）。 */
    private final boolean terminal;

    /**
     * 构造事件。
     *
     * @param name     事件名
     * @param payload  载荷对象
     * @param terminal 是否终态
     */
    public SseEvent(String name, Object payload, boolean terminal) {
        this.name = name;
        this.payload = payload;
        this.terminal = terminal;
    }

    /**
     * 获取事件名。
     *
     * @return 事件名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取载荷。
     *
     * @return 载荷对象
     */
    public Object getPayload() {
        return payload;
    }

    /**
     * 判断是否终态。
     *
     * @return 终态返回 {@code true}
     */
    public boolean isTerminal() {
        return terminal;
    }
}
