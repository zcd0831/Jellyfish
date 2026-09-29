package zcd.jellyfish.infra.plugin;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 插件上下文的存活标记：同一插件的根上下文与它的全部子上下文共享同一个实例。
 * <p>
 * <b>解决什么问题</b>：注册窗口的语义是「插件存活期内」而不是「{@code start()} 之内」——MCP 这类
 * 运行期才发现工具集的插件必须在启动之后继续注册。窗口放宽之后，原来靠文档约定守住的
 * 「插件已停、工具还能调」这道防线就必须改成运行期事实：插件停止时把标记置为关闭，
 * 此后任何注册 / 订阅 / 发布都当场失败，而不是悄悄落进注册表变成幽灵注册。
 * <p>
 * <b>为什么必须是共享实例而不是每个上下文各一个</b>：插件通过 {@code subContext} 派生的子上下文
 * 也持有注册能力，若它们各有一份标记，回收根上下文就管不住子上下文——幽灵注册会从这条缝里回来。
 * 因此标记跟着「插件」走，而不是跟着「上下文对象」走。
 * <p>
 * <b>为什么用 {@link AtomicBoolean} 而不是 {@code volatile boolean}</b>：它会被注册线程与停止线程
 * 并发触碰，而这里要的是一个明确的 happens-before 边界，不是为了省一把锁。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
final class ContextLifecycle {

    /** 是否已关闭：关闭之后该插件的一切注册、订阅与发布都被拒绝。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 判断上下文是否已失效。
     *
     * @return 已关闭返回 {@code true}
     */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * 关闭上下文：幂等，重复调用是空操作。
     */
    void close() {
        closed.set(true);
    }
}
