package zcd.jellyfish.core.subagent;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;
import zcd.jellyfish.core.runtime.AgentRunSnapshot;
import zcd.jellyfish.core.runtime.AgentRunStatus;
import zcd.jellyfish.core.runtime.AgentRuntime;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 子代理运行面板：把「这个会话现在有哪些子代理在跑」常驻显示在界面上。
 * <p>
 * <b>为什么要有一块常驻面板</b>：委派一旦开始，父回合就进了一段「没有输出、但确实在烧 token」的沉默期。
 * 在 {@code task} 工具行里滚动的进度只能说明「还在跑什么」，看不到<b>并行</b>的第二个、第三个。
 * 一个回合里子代理越多，这段沉默越难解释——面板把它变成一条可数的事实。
 * <p>
 * <b>为什么按父会话过滤</b>：运行表是进程级的，而面板是贴在某个会话的界面上的；
 * 不过滤的话，A 会话会显示 B 会话的子代理。
 * <p>
 * <b>为什么依赖 {@link AgentRuntime} 而不是 {@code RunRegistry}</b>：面板是运行时的观测面，
 * 应当只认识对外的门面；直接摸登记表会让面板在注册表改内部结构时被迫跟着改。
 * <p>
 * <b>必须快、必须只读</b>：面板处理器在渲染线程内联执行，阻塞一帧就是屏幕卡一帧；
 * 它也不得发布 {@code UiInvalidatedEvent}（那会形成「失效 → 收集 → 失效」的死循环）。
 * 这里只做一次快照遍历与字符串拼接，不做 I/O、不加锁。
 * <p>
 * <b>它不可能报「过期」</b>：面板显示的是「此刻」的事实，渲染线程取到哪一刻就是哪一刻——
 * 与状态栏片段同口径。真正的「什么时候重新问」由外壳的失效触发源决定。
 * <p>
 * <b>建议落停靠区（{@code DOCK}）而不是任一纵向栏</b>：这块面板讲的是「此刻正在发生什么」的瞬时事实，
 * 行是固定的短句（类型 · 状态 · 已运行 Ns），横向放正合适；而纵向栏是插件面板的稀缺资源
 * （左右两侧合计不超过终端宽的 1/3，超了外壳先砍右栏），占一条的代价是中等宽度的终端上
 * 另一侧的面板会被整块挤掉。停靠区是内核文档里点名的「最通用的面板落点」；
 * 与同样建议落 {@code DOCK} 的插件面板并存时，按 {@link SubAgentTools} 声明的 {@code order}
 * 让它先显示——run 的起止错过就无从追，而别的面板多是常驻信息。
 * <p>
 * 无状态（只持有运行时门面），可安全跨线程调用。
 *
 * @author zcd
 */
@Singleton
public final class SubAgentPanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板标题。 */
    private static final String TITLE = "子代理";

    /** 按开始时刻升序：界面上的行序不该随注册表内部的哈希顺序抖动。 */
    private static final Comparator<AgentRunSnapshot> BY_STARTED_AT = new Comparator<AgentRunSnapshot>() {

        @Override
        public int compare(AgentRunSnapshot left, AgentRunSnapshot right) {
            return Long.compare(left.getStartedAt(), right.getStartedAt());
        }
    };

    /** agent 运行时门面：取本会话在跑的 run。 */
    private final AgentRuntime runtime;

    /**
     * 构造面板。
     *
     * @param runtime agent 运行时门面，不可为 {@code null}
     */
    @Inject
    public SubAgentPanel(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    @Override
    public PanelContribution handle(PanelContributionRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null) {
            // 没有会话上下文就无从筛选：宁可空着，也不把别的会话的子代理贴到当前界面上
            return PanelContribution.empty();
        }
        List<AgentRunSnapshot> runs = new ArrayList<AgentRunSnapshot>();
        for (AgentRunSnapshot run : runtime.activeRuns()) {
            if (sessionId.equals(run.getParentSessionId())) {
                runs.add(run);
            }
        }
        if (runs.isEmpty()) {
            // 空贡献 = 不占区域。返回一块没有内容的空面板会让界面白占一块地方
            return PanelContribution.empty();
        }
        Collections.sort(runs, BY_STARTED_AT);
        List<UiLine> lines = new ArrayList<UiLine>(runs.size());
        for (AgentRunSnapshot run : runs) {
            lines.add(lineOf(run));
        }
        return PanelContribution.of(TITLE, lines, UiRegion.DOCK);
    }

    /**
     * 渲染一个 run 的一行：{@code 类型 · 状态 · 已运行 Ns}。
     *
     * @param run run 快照
     * @return 界面行，保证非 {@code null}
     */
    private static UiLine lineOf(AgentRunSnapshot run) {
        return UiLine.of(UiSegment.of(run.getAgentId()),
                UiSegment.of(" · " + statusTextOf(run.getStatus())),
                UiSegment.of(" · 已运行 " + elapsedSeconds(run) + "s"));
    }

    /**
     * 计算已运行秒数。
     * <p>
     * 不用 {@code finishedAt} 相减：本面板只显示未终结的 run，而系统时钟回拨会让差值为负——
     * 夹到 0 比显示「已运行 -3s」好。
     *
     * @param run run 快照
     * @return 已运行秒数，保证不小于 0
     */
    private static long elapsedSeconds(AgentRunSnapshot run) {
        long elapsedMillis = System.currentTimeMillis() - run.getStartedAt();
        return Math.max(0L, elapsedMillis / 1000L);
    }

    /**
     * 把 run 状态翻成界面上的一句话。
     *
     * @param status 状态
     * @return 状态文本
     */
    private static String statusTextOf(AgentRunStatus status) {
        switch (status) {
            case PENDING:
                return "排队中";
            case RUNNING:
                return "运行中";
            case WAITING_CHILDREN:
                return "等待子代理";
            case DONE:
                return "已完成";
            case FAILED:
                return "失败";
            case CANCELLED:
                return "已取消";
            case TRUNCATED:
                return "已截断";
            default:
                return status.name();
        }
    }
}
