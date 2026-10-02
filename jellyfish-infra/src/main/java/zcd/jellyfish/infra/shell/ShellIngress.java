package zcd.jellyfish.infra.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.ShellContribution;
import zcd.jellyfish.api.extension.ShellContributionStatus;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;
import zcd.jellyfish.infra.metrics.MetricNames;
import zcd.jellyfish.infra.metrics.MetricsRegistry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 外壳贡献的入站信箱：只负责「存、限、合并、取、丢」。
 * <p>
 * <b>它解决什么问题</b>：插件此前没有任何准实时推送路径——发个事件没人订阅，改扩展条目要等外壳下一帧
 * 来拉。贡献把那条路补上，而本类就是那条路上的缓冲：插件在<b>任意线程</b>调
 * {@code PluginContext.present}，外壳在<b>自己的线程</b>调 {@code ShellStreams.drainShell} 取走。
 * 两端因此都不必知道对方是哪个线程。
 * <p>
 * <b>为什么必须有个队列</b>：{@code present} 与交付不在同一时刻，甚至不在同一线程
 * （插件可以从自己的线程、或工具的输出泵线程推送）。「推了但还没轮到」是一个真实存在的状态，
 * 必须有地方承接它。这与 {@code ActionQueue} 是同一个判断。
 * <p>
 * <b>它与 {@code ActionQueue} 的两处关键差别</b>：
 * <ul>
 *     <li><b>没有回合边界</b>：贡献与回合无关（插件可以在没有回合在跑的时候推一条状态提示），
 *     因此没有 {@code beginTurn} / {@code endTurn} 那个窗口，只有「有界 + 合并 + 满即丢」；</li>
 *     <li><b>丢的是最新一条</b>：动作队列满了丢新投递的那条（内容各异，旧的更该被保留），
 *     而通知类贡献里「最新」最有价值——丢最旧会让界面停在中间态，看上去像卡住了。</li>
 * </ul>
 * <p>
 * <b>它是可丢的，这是刻意的</b>：贡献进的是尽力 lane。内核回合事件（正文 / 思考 / 工具 / 终态）
 * 走可靠 lane，那条路没有队列、不丢不乱序。两条路合并会把「可丢 / 不可丢」这一层区分抹掉。
 * <p>
 * <b>它不碰会话与回合</b>：本类<b>不注入</b> {@code SessionManager}，也不注入 {@code AgentHarness}
 * ——「插件不能新开会话、不能起回合」这条硬约束因此不是靠文档约定，而是在这里结构性地成立：
 * 这个类里没有任何能创建或改写会话状态的调用路径。
 * <p>
 * <b>它不是事件总线</b>：没有订阅、没有广播、没有处理器注册，只有一条有界的入站信箱。
 * 内核与插件之间的通知仍然只走 {@code EventChannel}。
 * <p>
 * <b>归属按 owner 命名空间回收</b>（与注册表、动作队列同一套规则、同一时刻）：插件停止时整桶丢弃，
 * 之后 {@code present} 由 {@code ContextLifecycle} 挡在插件上下文那一层。
 * <p>
 * 线程安全：全部读写在同一把锁上——插件线程、外壳线程与插件停止线程会同时碰到它。
 *
 * @author zcd
 */
@Singleton
public final class ShellIngress {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ShellIngress.class);

    /**
     * 每 owner 未交付贡献的缺省上界。
     * <p>
     * 有界是硬要求：队列是内核自有的内存结构，插件写得再多、再快，也不该把内核吃干。
     * 满了就丢并计数——贡献是「顺手显示一下」，不是「必须完成的事实」。
     */
    public static final int DEFAULT_CAPACITY = 64;

    /** 每 owner 未交付上界。 */
    private final int capacity;

    /** 指标注册表。 */
    private final MetricsRegistry metrics;

    /** 未交付贡献，按入队顺序；跨 owner 的顺序不承诺，这里恰好是「先到先出」。 */
    private final Deque<Entry> pending = new ArrayDeque<Entry>();

    /** {@code owner} → 未交付条数；用于按 owner 限流。 */
    private final Map<String, Integer> countsByOwner = new HashMap<String, Integer>();

    /** {@code owner + \u0000 + key} → 队列里那条未交付项；只覆盖带 key 的通知。 */
    private final Map<String, Entry> byKey = new HashMap<String, Entry>();

    /** 下一个入队序号，仅用于诊断。 */
    private long sequence;

    /**
     * 构造信箱。
     */
    @Inject
    public ShellIngress(MetricsRegistry metrics) {
        this(metrics, DEFAULT_CAPACITY);
    }

    /**
     * 构造信箱并指定每 owner 容量，供单元测试使用。
     *
     * @param metrics  指标注册表，不可为 {@code null}
     * @param capacity 每 owner 未交付上界，必须为正数
     */
    ShellIngress(MetricsRegistry metrics, int capacity) {
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        if (capacity <= 0) {
            throw new IllegalArgumentException("shell ingress capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 投递一条贡献。
     * <p>
     * <b>只入队，绝不在调用者的栈上交付给外壳</b>：交付发生在外壳自己调 {@link #drain()} 的时候。
     * 这条是「慢外壳拖不住插件线程」的全部依据。
     * <p>
     * <b>合并只在未交付范围内做</b>：已经取走的无法回收，因此同 key 的替换只作用于队列里的那一条，
     * 位置保持不变（「原地更新」，不会把进度类通知顶到队尾去）。
     *
     * @param owner        投递者的 owner 命名空间，不可为空白
     * @param contribution 贡献，不可为 {@code null}
     * @return {@link ShellContributionStatus#ACCEPTED} /
     *         {@link ShellContributionStatus#COALESCED} /
     *         {@link ShellContributionStatus#DROPPED_QUEUE_FULL}
     */
    public ShellContributionStatus present(String owner, ShellContribution contribution) {
        Objects.requireNonNull(contribution, "contribution must not be null");
        String bucket = bucketOf(owner);
        synchronized (this) {
            if (contribution.getKey() != null && contribution.getKind() == ShellContribution.Kind.NOTICE) {
                Entry existing = byKey.get(keyOf(bucket, contribution.getKey()));
                if (existing != null) {
                    existing.contribution = contribution;
                    metrics.increment(MetricNames.PLUGIN_SHELL_CONTRIBUTION_COALESCED);
                    return ShellContributionStatus.COALESCED;
                }
            }
            if (countOf(bucket) >= capacity) {
                metrics.increment(MetricNames.PLUGIN_SHELL_CONTRIBUTION_DROPPED);
                LOG.warn("外壳贡献队列已满，丢弃最新一条: owner={} capacity={} kind={}",
                        bucket, capacity, contribution.getKind());
                return ShellContributionStatus.DROPPED_QUEUE_FULL;
            }
            Entry entry = new Entry(bucket, contribution, sequence++);
            pending.addLast(entry);
            countsByOwner.put(bucket, countOf(bucket) + 1);
            if (contribution.getKey() != null && contribution.getKind() == ShellContribution.Kind.NOTICE) {
                byKey.put(keyOf(bucket, contribution.getKey()), entry);
            }
            metrics.increment(MetricNames.PLUGIN_SHELL_CONTRIBUTION_ACCEPTED);
            return ShellContributionStatus.ACCEPTED;
        }
    }

    /**
     * 记一次<b>未入队</b>的投递结果：会话不存在、或当前外壳不渲染贡献。
     * <p>
     * 这两档判断需要会话域与运行时信息，而本类刻意不碰它们（见类注释），
     * 因此由 {@code PluginContextImpl} 判定，再回到这里记账——指标必须只有一处写入，
     * 否则 {@code accepted + coalesced + dropped} 这条账就对不上。
     *
     * @param owner  投递者的 owner 命名空间
     * @param status 结果，只能是 {@link ShellContributionStatus#DROPPED_NO_SESSION}
     *               或 {@link ShellContributionStatus#DROPPED_NO_RENDERER}
     * @return 原样返回 {@code status}
     */
    public ShellContributionStatus recordRejection(String owner, ShellContributionStatus status) {
        metrics.increment(MetricNames.PLUGIN_SHELL_CONTRIBUTION_DROPPED);
        if (LOG.isDebugEnabled()) {
            LOG.debug("外壳贡献未入队: owner={} status={}", bucketOf(owner), status);
        }
        return status;
    }

    /**
     * 取走全部未交付贡献，按入队顺序。
     * <p>
     * <b>同 owner 保证 FIFO</b>；跨 owner 不做承诺（两个插件的两条通知之间没有「谁在前」的语义）。
     * 取走的条目不再参与合并——它们已经离开本信箱了。
     *
     * @return 未交付条目，无贡献时为空列表
     */
    public synchronized List<Entry> drain() {
        if (pending.isEmpty()) {
            return new ArrayList<Entry>();
        }
        List<Entry> drained = new ArrayList<Entry>(pending);
        pending.clear();
        countsByOwner.clear();
        byKey.clear();
        return drained;
    }

    /**
     * 按 owner 命名空间丢弃未交付贡献：{@code owner} 自身与 {@code owner::*} 一并清掉。
     * <p>
     * 与注册表、动作队列同一套规则、同一时刻（插件停止时）。顺序不能反——必须先清空，
     * 外壳随后可能收到一条插件状态变更触发的失效重拉，那时该 owner 的贡献应当已经不在队列里了。
     *
     * @param owner owner 命名空间根，可为 {@code null}（此时不做任何事）
     * @return 被丢弃的条目数量
     */
    public synchronized int reset(String owner) {
        if (owner == null) {
            return 0;
        }
        int dropped = 0;
        Iterator<Entry> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Entry next = iterator.next();
            if (under(next.owner, owner)) {
                iterator.remove();
                if (next.getContribution().getKey() != null) {
                    byKey.remove(keyOf(next.owner, next.getContribution().getKey()));
                }
                countsByOwner.put(next.owner, countOf(next.owner) - 1);
                dropped++;
            }
        }
        if (dropped > 0) {
            LOG.info("已丢弃插件未交付的外壳贡献: owner={} contributions={}", owner, dropped);
        }
        return dropped;
    }

    /**
     * 判断某个 owner 是否落在命名空间根之下。
     *
     * @param candidate 候选 owner
     * @param root      命名空间根
     * @return 属于该命名空间返回 {@code true}
     */
    private static boolean under(String candidate, String root) {
        return candidate.equals(root) || candidate.startsWith(root + PluginOwnerNamespace.SEPARATOR);
    }

    /**
     * 取某个 owner 的未交付条数。
     *
     * @param bucket owner
     * @return 条数，无记录时为 {@code 0}
     */
    private int countOf(String bucket) {
        Integer count = countsByOwner.get(bucket);
        return count == null ? 0 : count;
    }

    /**
     * 归一化 owner：空白一律落到一个固定的无名桶，避免 {@code null} 作为 map 键。
     *
     * @param owner owner，可为 {@code null}
     * @return 非 {@code null} 的桶名
     */
    private static String bucketOf(String owner) {
        return owner == null || owner.trim().isEmpty() ? "" : owner;
    }

    /**
     * 组装合并键。
     *
     * @param owner owner
     * @param key   插件给的合并键
     * @return 内部索引键
     */
    private static String keyOf(String owner, String key) {
        return owner + '\u0000' + key;
    }

    /**
     * 一条尚未交付的贡献：owner + 内容 + 入队序号。
     * <p>
     * 内容字段可变：同 key 的后到者原地替换它（见 {@link #present}）。读取端看到的一定是完整的一条
     * ——要么旧的、要么新的，不会是半成品。
     *
     * @author zcd
     */
    public static final class Entry {

        /** 投递者的 owner 命名空间。 */
        private final String owner;

        /** 入队序号，仅用于诊断与测试断言顺序。 */
        private final long sequence;

        /** 贡献内容；同 key 合并时被替换。 */
        private volatile ShellContribution contribution;

        /**
         * 构造条目。
         *
         * @param owner        投递者 owner
         * @param contribution 贡献
         * @param sequence     入队序号
         */
        private Entry(String owner, ShellContribution contribution, long sequence) {
            this.owner = owner;
            this.contribution = contribution;
            this.sequence = sequence;
        }

        /**
         * 获取投递者的 owner 命名空间。
         *
         * @return owner，保证非 {@code null}
         */
        public String getOwner() {
            return owner;
        }

        /**
         * 获取贡献内容。
         *
         * @return 贡献，保证非 {@code null}
         */
        public ShellContribution getContribution() {
            return contribution;
        }

        /**
         * 获取入队序号。
         *
         * @return 序号，单调递增
         */
        public long getSequence() {
            return sequence;
        }

        @Override
        public String toString() {
            return "Entry{owner=" + owner + ", sequence=" + sequence + ", contribution=" + contribution + '}';
        }
    }
}
