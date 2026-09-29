package zcd.jellyfish.core;

/**
 * 一次顶层回合内的委派作用域：当前委派深度、已派生的子代理数，以及两者各自的上限。
 * <p>
 * <b>为什么需要它</b>：子代理的作用域是「一次顶层回合」，而不是「一次工具调用」——同一个回合里
 * 模型可以连着委派好几次，也可能某个子代理再往下委派。这笔账必须挂在回合上，且要让两处同时看得见：
 * 循环器（知道自己进入了嵌套回合）与委派方（决定放不放行）。它既不是会话状态（不随会话持久化），
 * 也不是全局状态（并发回合互不影响），于是落在「每个 react 线程一份」的作用域里。
 * <p>
 * <b>两个上限都要</b>：深度挡的是「一条链走多深」，预算挡的是「一层扇出多少」。
 * 只有深度时，一层派出 32 个子代理仍然成立；只有预算时，一条链可以很深。两者正交。
 * <p>
 * <b>线程封闭</b>：本类不做同步，由 {@link RunScopes} 的 {@code ThreadLocal} 保证同一时刻只有一条
 * 线程访问它，且嵌套回合是<b>内联</b>执行的（与父回合同线程），因此不存在跨线程共享。
 *
 * @author zcd
 */
public final class RunScope {

    /** 允许的最大委派层数。 */
    private final int maxDepth;

    /** 本次顶层回合允许派生的子代理总数。 */
    private final int maxSpawnsPerTurn;

    /** 当前委派深度：顶层回合为 {@code 0}，每进入一个嵌套回合加一。 */
    private int depth;

    /** 本次顶层回合已派生的子代理数。 */
    private int spawnCount;

    /**
     * 构造委派作用域，仅供 {@link RunScopes} 调用。
     *
     * @param maxDepth          允许的最大委派层数，{@code 0} 表示禁止委派
     * @param maxSpawnsPerTurn  本次顶层回合允许派生的子代理总数，保证为正
     */
    RunScope(int maxDepth, int maxSpawnsPerTurn) {
        this.maxDepth = maxDepth;
        this.maxSpawnsPerTurn = maxSpawnsPerTurn;
    }

    /**
     * 判断此刻还能不能派出一个新的子代理。
     * <p>
     * 委派方应当在<b>创建子会话之前</b>调用它：拿不到名额就不该产生任何副作用。
     *
     * @return 深度与预算都未用尽返回 {@code true}
     */
    public boolean canDelegate() {
        return depth < maxDepth && spawnCount < maxSpawnsPerTurn;
    }

    /**
     * 记一次派生。
     * <p>
     * 与 {@link #canDelegate()} 配合使用：先判定再记数，不能只记不判（否则预算形同虚设）。
     */
    public void recordSpawn() {
        spawnCount++;
    }

    /**
     * 进入一个嵌套回合：深度加一，由 {@code ReActLooper.runNested} 在 {@code finally} 里配对
     * {@link #leave()}，因此异常与取消路径都不会让深度虚高。
     */
    void enter() {
        depth++;
    }

    /**
     * 退出一个嵌套回合：深度减一。
     */
    void leave() {
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
        return spawnCount;
    }

    /**
     * 获取允许的最大委派层数。
     *
     * @return 最大委派层数，保证非负
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * 获取本次顶层回合允许派生的子代理总数。
     *
     * @return 子代理总数上限，保证为正
     */
    public int getMaxSpawnsPerTurn() {
        return maxSpawnsPerTurn;
    }

    @Override
    public String toString() {
        return "RunScope{depth=" + depth + '/' + maxDepth
                + ", spawns=" + spawnCount + '/' + maxSpawnsPerTurn + '}';
    }
}
