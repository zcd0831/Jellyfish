package zcd.jellyfish.server.dto;

/**
 * 取消结果：{@code POST /sessions/{id}/cancel} 的返回体。
 * <p>
 * <b>为什么「没取消到」也是 200</b>：该会话此刻可能本来就没有在途回合（用户手快了、或回合刚自己结束）。
 * 这不是错误，而是一个事实——{@code cancelled=false} 如实表达它，比 404 或 409 更准确。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CancelResult {

    /** 是否真的取消到了一个在途回合。 */
    private final boolean cancelled;

    /**
     * 构造取消结果。
     *
     * @param cancelled 是否取消到在途回合
     */
    public CancelResult(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * 判断是否取消到在途回合。
     *
     * @return 取消到返回 {@code true}
     */
    public boolean isCancelled() {
        return cancelled;
    }
}
