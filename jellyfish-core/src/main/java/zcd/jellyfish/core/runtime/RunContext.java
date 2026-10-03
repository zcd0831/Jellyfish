package zcd.jellyfish.core.runtime;

/**
 * 一次执行路径上的委派上下文：当前深度、这条路径属于哪个 run，以及它持有的并发许可。
 * <p>
 * <b>为什么需要它</b>：委派的账挂在「一次顶层回合」上，而不是「一次工具调用」上——同一个回合里
 * 模型可以连着委派，子代理也可能再往下委派。这笔账要让两处同时看得见：循环器（知道自己进入了
 * 嵌套回合、当前在第几层）与委派方（决定放不放行）。它既不是会话状态（不持久化），
 * 也不是全局状态（并发回合互不影响），于是落在「每条执行线程一份」的上下文里。
 * <p>
 * <b>什么在树里、什么在上下文里</b>：跨线程共享的派生计数与上限在 {@link RunTree}（按引用共享）；
 * 「当前这条路径走到第几层」与「我是不是某个 run」只对本线程有意义，留在本类。
 * <p>
 * <b>深度为什么可变</b>：嵌套回合由 {@code ReActLooper.runNested} 在同一线程上进入 / 退出，
 * 用 {@link #enter()}/{@link #leave()} 配对增减，异常与取消路径也能靠 {@code finally} 复原。
 * 并行落地后每个 run 各自持有独立上下文（由调度器在执行线程上设置），因此这里的可变性
 * 仍然只发生在单线程内。
 *
 * @author zcd
 */
public final class RunContext {

    /** 这棵 run 树共享的账本：派生上限与已派生数。 */
    private final RunTree tree;

    /** 当前委派深度：顶层回合为 {@code 0}，每进入一个嵌套回合加一。 */
    private int depth;

    /** 本路径所属的 run 标识；顶层回合的根上下文为 {@code null}。 */
    private final String runId;

    /** 本路径所属 run 树的根 run 标识；根上下文为 {@code null}。 */
    private final String rootRunId;

    /** 本路径持有的并发许可；根上下文为 {@code null}。 */
    private final RunPermit permit;

    /**
     * 构造上下文，仅供 {@link RunContextHolder} 与调度器调用。
     *
     * @param tree      这棵 run 树共享的账本，不可为 {@code null}
     * @param depth     初始委派深度
     * @param runId     本路径所属 run 标识，可为 {@code null}（顶层回合）
     * @param rootRunId 本路径所属 run 树的根标识，可为 {@code null}（顶层回合）
     * @param permit    本路径持有的并发许可，可为 {@code null}（顶层回合）
     */
    RunContext(RunTree tree, int depth, String runId, String rootRunId, RunPermit permit) {
        this.tree = tree;
        this.depth = depth;
        this.runId = runId;
        this.rootRunId = rootRunId;
        this.permit = permit;
    }

    /**
     * 判断此刻还能不能派出一个新的子代理。
     * <p>
     * 委派方应当在<b>创建子会话之前</b>调用它：拿不到名额就不该产生任何副作用。
     *
     * @return 深度与预算都未用尽返回 {@code true}
     */
    public boolean canDelegate() {
        return tree.canDelegate(depth);
    }

    /**
     * 原子地「判定并占用」一个派生名额。
     * <p>
     * 与 {@link #canDelegate()} 的区别是它把判定与记数合成一步：并行时多个父 run 会同时尝试派生，
     * 两步之间有超发窗口（详见 {@link RunTree#tryAcquireSpawn(int)}）。因此真正占名额必须用它。
     *
     * @return 占到名额返回 {@code true}；深度或总数已达上限返回 {@code false}
     */
    public boolean tryAcquireSpawn() {
        return tree.tryAcquireSpawn(depth);
    }

    /**
     * 进入一个嵌套回合：深度加一，由 {@code ReActLooper.runNested} 在 {@code finally} 里配对
     * {@link #leave()}，因此异常与取消路径都不会让深度虚高。
     */
    public void enter() {
        depth++;
    }

    /**
     * 退出一个嵌套回合：深度减一。
     */
    public void leave() {
        depth--;
    }

    /**
     * 获取当前委派深度。
     *
     * @return 委派深度，顶层回合为 {@code 0}
     */
    public int getDepth() {
        return depth;
    }

    /**
     * 获取本次顶层回合已派生的子代理数。
     *
     * @return 已派生的子代理数
     */
    public int getSpawnCount() {
        return tree.getSpawnCount();
    }

    /**
     * 获取允许的最大委派层数。
     *
     * @return 最大委派层数，保证非负
     */
    public int getMaxDepth() {
        return tree.getMaxDepth();
    }

    /**
     * 获取本次顶层回合允许派生的子代理总数。
     *
     * @return 子代理总数上限，保证为正
     */
    public int getMaxSpawnsPerTurn() {
        return tree.getMaxSpawnsPerTurn();
    }

    /**
     * 获取本路径所属的 run 标识。
     *
     * @return run 标识；顶层回合的根上下文为 {@code null}
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取本路径所属 run 树的根标识。
     *
     * @return 根 run 标识；顶层回合的根上下文为 {@code null}
     */
    public String getRootRunId() {
        return rootRunId;
    }

    /**
     * 获取这棵 run 树累计消耗的 token。
     *
     * @return 累计 token
     */
    public long getTreeTokens() {
        return tree.getTreeTokens();
    }

    /**
     * 累加这棵树消耗的 token。
     *
     * @param delta 本次增量
     * @return 累加后的树总消耗
     */
    public long recordTreeTokens(long delta) {
        return tree.addTokens(delta);
    }

    /**
     * 获取单个 run 的累计 token 上限。
     *
     * @return token 上限；{@code 0} 表示不限制
     */
    public long getRunTokenBudget() {
        return tree.getRunTokenBudget();
    }

    /**
     * 获取一棵 run 树的累计 token 上限。
     *
     * @return token 上限；{@code 0} 表示不限制
     */
    public long getTreeTokenBudget() {
        return tree.getTreeTokenBudget();
    }

    /**
     * 获取这棵 run 树共享的账本。
     *
     * @return 共享账本，保证非 {@code null}
     */
    RunTree tree() {
        return tree;
    }

    /**
     * 获取本路径持有的并发许可。
     *
     * @return 并发许可；顶层回合返回 {@code null}
     */
    RunPermit getPermit() {
        return permit;
    }

    @Override
    public String toString() {
        return "RunContext{depth=" + depth + '/' + tree.getMaxDepth()
                + ", spawns=" + tree.getSpawnCount() + '/' + tree.getMaxSpawnsPerTurn()
                + ", run=" + runId + '}';
    }
}
