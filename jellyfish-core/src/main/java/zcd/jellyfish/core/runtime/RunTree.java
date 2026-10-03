package zcd.jellyfish.core.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一棵 run 树共享的账本：能派生多少、能烧多少 token。
 * <p>
 * <b>为什么从上下文里拆出来</b>：深度是「当前这一条链走到第几层」，属于单个 run 的上下文；
 * 而「本回合一共派了多少个」「这棵树一共烧了多少 token」是全树共享的账。并行之后多个 run 会并发
 * 读写后者，因此它必须是一个跨线程按引用共享、且自身线程安全的对象。
 * <p>
 * <b>为什么 {@link #tryAcquireSpawn(int)} 把「判定」与「记数」合成一步</b>：并行时多个父 run 会
 * 同时尝试派生兄弟。先 {@code canDelegate()} 再 {@code recordSpawn()} 的两步之间有一个窗口，
 * 窗口里每个线程都看到「还剩名额」，于是集体超发——而上限的意义恰恰是<b>不</b>超发。合成一步 CAS
 * 之后，「读到的剩余额度」与「占用它」原子发生。
 * <p>
 * <b>token 账为什么在这里</b>：单 run 上限与树上限都要看这棵树的累计消耗，而树是共享的；
 * 把累计值挂在树上，每个 run 只往里加自己的增量，任何线程都能读到当前总量。
 *
 * @author zcd
 */
final class RunTree {

    /** 允许的最大委派层数。 */
    private final int maxDepth;

    /** 本次顶层回合允许派生的子代理总数。 */
    private final int maxSpawnsPerTurn;

    /** 单个 run 的累计 token 上限；{@code 0} 表示不限制。 */
    private final long runTokenBudget;

    /** 一棵 run 树的累计 token 上限；{@code 0} 表示不限制。 */
    private final long treeTokenBudget;

    /** 本次顶层回合已派生的子代理数。 */
    private final AtomicInteger spawnCount = new AtomicInteger();

    /** 这棵 run 树累计消耗的 token。 */
    private final AtomicLong treeTokens = new AtomicLong();

    /**
     * 构造一棵 run 树的账本。
     *
     * @param maxDepth         允许的最大委派层数，{@code 0} 表示禁止委派
     * @param maxSpawnsPerTurn 本次顶层回合允许派生的子代理总数，保证为正
     * @param runTokenBudget   单个 run 的累计 token 上限，{@code 0} 表示不限制
     * @param treeTokenBudget  一棵 run 树的累计 token 上限，{@code 0} 表示不限制
     */
    RunTree(int maxDepth, int maxSpawnsPerTurn, long runTokenBudget, long treeTokenBudget) {
        this.maxDepth = maxDepth;
        this.maxSpawnsPerTurn = maxSpawnsPerTurn;
        this.runTokenBudget = runTokenBudget;
        this.treeTokenBudget = treeTokenBudget;
    }

    /**
     * 判断给定深度下还能不能派出一个新的子代理。
     * <p>
     * 只读判定，供「尽早拒绝」使用；真正占名额请用 {@link #tryAcquireSpawn(int)}。
     *
     * @param depth 当前委派深度（顶层回合为 {@code 0}）
     * @return 深度与预算都未用尽返回 {@code true}
     */
    boolean canDelegate(int depth) {
        return depth < maxDepth && spawnCount.get() < maxSpawnsPerTurn;
    }

    /**
     * 原子地「判定并占用」一个派生名额。
     *
     * @param depth 当前委派深度
     * @return 占到名额返回 {@code true}；深度或总数已达上限返回 {@code false}
     */
    boolean tryAcquireSpawn(int depth) {
        while (true) {
            int current = spawnCount.get();
            if (depth >= maxDepth || current >= maxSpawnsPerTurn) {
                return false;
            }
            if (spawnCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 累加这棵树消耗的 token。
     *
     * @param delta 本次增量；非正数忽略
     * @return 累加后的树总消耗
     */
    long addTokens(long delta) {
        return delta <= 0L ? treeTokens.get() : treeTokens.addAndGet(delta);
    }

    /**
     * 获取本次顶层回合已派生的子代理数。
     *
     * @return 已派生的子代理数
     */
    int getSpawnCount() {
        return spawnCount.get();
    }

    /**
     * 获取这棵树累计消耗的 token。
     *
     * @return 累计 token
     */
    long getTreeTokens() {
        return treeTokens.get();
    }

    /**
     * 获取允许的最大委派层数。
     *
     * @return 最大委派层数，保证非负
     */
    int getMaxDepth() {
        return maxDepth;
    }

    /**
     * 获取本次顶层回合允许派生的子代理总数。
     *
     * @return 子代理总数上限，保证为正
     */
    int getMaxSpawnsPerTurn() {
        return maxSpawnsPerTurn;
    }

    /**
     * 获取单个 run 的累计 token 上限。
     *
     * @return token 上限；{@code 0} 表示不限制
     */
    long getRunTokenBudget() {
        return runTokenBudget;
    }

    /**
     * 获取一棵 run 树的累计 token 上限。
     *
     * @return token 上限；{@code 0} 表示不限制
     */
    long getTreeTokenBudget() {
        return treeTokenBudget;
    }

    @Override
    public String toString() {
        return "RunTree{spawns=" + spawnCount.get() + '/' + maxSpawnsPerTurn
                + ", maxDepth=" + maxDepth + ", tokens=" + treeTokens.get() + '}';
    }
}
