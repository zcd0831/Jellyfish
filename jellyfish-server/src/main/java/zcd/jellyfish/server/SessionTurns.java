package zcd.jellyfish.server;

import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.Responses;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 每会话在途回合表：把「同一会话同时只能有一个回合」这条约束钉在一个地方。
 * <p>
 * <b>为什么必须有它</b>：内核的对话执行是「先 append 用户消息 → 循环调用模型 → append 助手消息」，
 * 两个回合并行跑在同一个会话上会把消息历史交错写坏（用户 A 的提问后面跟着用户 B 的提问，
 * 而两者的回答各按自己的上下文生成）。HTTP 是多客户端并发的，这个约束在内核里没有、必须由外壳提供；
 * 单客户端外壳（CLI / TUI）天然只有一个回合，从来不需要它。
 * <p>
 * <b>为什么必须在起回合之前就把槽位占住</b>：{@code AgentHarness.chat} 一返回，回合任务就已经提交到
 * {@code react} 线程池，它做的第一件事就是 append 用户消息。若「先起回合、再登记」，两个并发请求
 * 都会先 append 再被拒——被拒的那个已经污染了会话历史。<b>占位必须早于起回合</b>，因此这里用
 * {@link Semaphore#tryAcquire()} 而不是事后登记。
 * <p>
 * <b>为什么用 {@link Semaphore}(1) 而不是 {@code ReentrantLock}</b>：后者允许<b>同一线程</b>重入，
 * 于是「同一个线程误占两次」这种真 bug 会被它静默放过。信号量是非重入的，同样的错误当场失败。
 * <p>
 * <b>为什么锁实例不回收</b>：回收需要判断「没有别的线程正准备拿同一个信号量」，而那个判断本身需要
 * 另一把锁，得回的复杂度远高于收益——每个会话一个信号量的开销可以忽略，而会话数由使用规模决定。
 * <p>
 * <b>为什么冲突返回 409 而不是排队</b>：排队会让第二个请求在无提示的情况下等一个可能几分钟的回合，
 * 而调用方拿不到任何进度；返回 409 让客户端自己决定「等一下再发」还是「先取消当前回合」。
 * <p>
 * 线程安全：槽位表用 {@link ConcurrentHashMap}，互斥用每会话一个信号量。
 *
 * @author zcd
 */
public final class SessionTurns {

    /** 在途回合表：会话标识 → 当前回合（供取消用）。 */
    private final ConcurrentHashMap<String, ReActTurn> turns = new ConcurrentHashMap<String, ReActTurn>();

    /** 每会话槽位：非重入，不回收。 */
    private final ConcurrentHashMap<String, Semaphore> slots =
            new ConcurrentHashMap<String, Semaphore>();

    /**
     * 占用一个会话的回合槽位（非阻塞）。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 已占用的槽位，供 {@link #release} 释放
     * @throws ApiException 该会话已有在途回合时抛出（409 TURN_IN_PROGRESS）
     */
    public Semaphore acquire(String sessionId) {
        Semaphore slot = slots.computeIfAbsent(sessionId, key -> new Semaphore(1));
        if (!slot.tryAcquire()) {
            throw new ApiException(Responses.CONFLICT, "TURN_IN_PROGRESS",
                    "该会话已有在途回合，请等待其结束或先取消：" + sessionId);
        }
        return slot;
    }

    /**
     * 把已启动的回合句柄绑定到槽位，供 {@link #cancel(String)} 使用。
     *
     * @param sessionId 会话标识
     * @param turn      回合句柄，不可为 {@code null}
     */
    public void bind(String sessionId, ReActTurn turn) {
        turns.put(sessionId, turn);
    }

    /**
     * 释放槽位。
     *
     * @param sessionId 会话标识
     * @param slot      {@link #acquire} 返回的信号量，不可为 {@code null}
     */
    public void release(String sessionId, Semaphore slot) {
        turns.remove(sessionId);
        // 只在「确实被占着」时归还：重复 release 会把许可数涨到 2，从而静默破坏互斥
        if (slot.availablePermits() == 0) {
            slot.release();
        }
    }

    /**
     * 取消一个会话的在途回合。
     * <p>
     * 在「已占用槽位、尚未绑定回合」的极小窗口里会返回 {@code false}——该窗口只覆盖
     * 「调用 {@code chat} 到拿到句柄」这几微秒，而回合此刻还没有可取消的工作。
     *
     * @param sessionId 会话标识
     * @return 真的取消到了在途回合返回 {@code true}；该会话没有在途回合时返回 {@code false}
     */
    public boolean cancel(String sessionId) {
        ReActTurn current = turns.get(sessionId);
        if (current == null || current.isDone()) {
            return false;
        }
        current.cancel();
        return true;
    }

    /**
     * 判断某会话是否有在途回合。
     * <p>
     * 仅供测试与诊断使用：读到结果的那一刻它就可能已经变了，因此不参与任何业务判定。
     *
     * @param sessionId 会话标识
     * @return 有在途回合返回 {@code true}
     */
    boolean hasActive(String sessionId) {
        ReActTurn current = turns.get(sessionId);
        return current != null && !current.isDone();
    }
}
