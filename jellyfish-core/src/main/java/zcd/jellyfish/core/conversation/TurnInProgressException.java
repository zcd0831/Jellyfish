package zcd.jellyfish.core.conversation;

import zcd.jellyfish.api.JellyfishException;

/**
 * 同一会话已有在途回合时再次提交所抛出的异常。
 * <p>
 * <b>为什么需要独立的类型</b>：外壳对它的处置与其它失败完全不同——它是一次<b>并发冲突</b>，
 * 客户端应当「等一下再发」或「先取消当前回合」，而不是「看日志排故障」。
 * 因此 Server 把它映射成 409、TUI 把它显示成一条「回合进行中」的提示；
 * 若混在 {@link JellyfishException} 里，外壳只能去解析文案来判断，而文案是最不该被依赖的东西。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public class TurnInProgressException extends JellyfishException {

    /**
     * 序列化版本号。
     * <p>
     * 内核没有把异常跨进程序列化的路径，因此它眼下只满足可序列化类的规范；显式声明而不是交给默认计算，
     * 是因为异常是<b>公共契约</b>——子类必须各自声明（序列化机制只看类自身声明的那个字段，不继承）。
     */
    private static final long serialVersionUID = 1L;

    /** 冲突的会话标识。 */
    private final String sessionId;

    /**
     * 构造异常。
     *
     * @param sessionId 已有在途回合的会话标识，可为 {@code null}
     */
    public TurnInProgressException(String sessionId) {
        super("该会话已有在途回合，请等待其结束或先取消：" + sessionId);
        this.sessionId = sessionId;
    }

    /**
     * 获取冲突的会话标识。
     *
     * @return 会话标识，可能为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }
}
