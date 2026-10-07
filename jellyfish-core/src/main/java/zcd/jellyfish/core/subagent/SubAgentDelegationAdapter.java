package zcd.jellyfish.core.subagent;

import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationQuota;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.SubAgentPort;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;

/**
 * 面向插件的委派端口实现：把 api 侧的请求翻成内核的一次委派。
 * <p>
 * <b>它只有翻译，没有任何策略</b>：准入、并发、深度、预算、取消传播、用量归集、归档、生命周期事件
 * 全部由 {@link SubAgentLauncher} 负责，因此「插件驱动的 run」与「{@code task} 触发的 run」不是
 * 「两处都记得这么写」，而是<b>同一条代码路径</b>。这是 P2 验收里「行为一致」的落地方式。
 * <p>
 * <b>为什么这里可以不阻塞地返回</b>：{@link SubAgentLauncher#spawn} 立即返回句柄，等待留给
 * {@link DelegationHandle#await()}，于是编排方可以「先连发 N 个派生、再逐个等」。
 * <p>
 * <b>不给子代理转进度</b>：{@code ReActListener} 是内核类型，插件看不到也不需要。
 * 编排方要展示进度，应当在自己的工具里往工具输出旁路写（{@code ToolCallOutput}）——
 * 那本来就是「这次工具调用在做什么」的正确表达位置。
 * <p>
 * 无状态（除每句柄的结果缓存外），可安全跨线程调用。
 *
 * @author zcd
 */
@Singleton
public final class SubAgentDelegationAdapter implements SubAgentPort {

    /** 子代理委派器：准入、派生与收尾的唯一实现。 */
    private final SubAgentLauncher launcher;

    /**
     * 构造适配器。
     *
     * @param launcher 子代理委派器，不可为 {@code null}
     */
    @Inject
    public SubAgentDelegationAdapter(SubAgentLauncher launcher) {
        this.launcher = Objects.requireNonNull(launcher, "launcher must not be null");
    }

    @Override
    public DelegationHandle spawn(DelegationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SubAgentCall call = new SubAgentCall(request.getParentSessionId(), request.getAgentId(),
                request.getPrompt(), request.getCancellationToken());
        return new Handle(launcher, launcher.spawn(call, null));
    }

    @Override
    public DelegationQuota quota() {
        // 额度判定只有一份实现（在委派器里）：查询与 spawn 的拒绝理由因此必然同源
        return launcher.quota();
    }

    /**
     * 一次已派生委派的句柄：等待时把内核结果翻成 api 结果。
     * <p>
     * 静态嵌套类而不是内部类：它只该持有「能收尾的委派器」与内核句柄，不该顺带持有整个适配器。
     */
    private static final class Handle implements DelegationHandle {

        /** 子代理委派器：等待与收尾的入口。 */
        private final SubAgentLauncher launcher;

        /** 内核句柄。 */
        private final SubAgentRunHandle run;

        /** 已返回过的结果：{@code await} 的契约是「重复调用返回同一个结果」。 */
        private DelegationResult result;

        /**
         * 构造句柄。
         *
         * @param launcher 子代理委派器，不可为 {@code null}
         * @param run      内核句柄，不可为 {@code null}
         */
        private Handle(SubAgentLauncher launcher, SubAgentRunHandle run) {
            this.launcher = launcher;
            this.run = run;
        }

        @Override
        public String runId() {
            return run.getRunId();
        }

        @Override
        public DelegationResult await() {
            if (result == null) {
                result = toResult(run.getRunId(), launcher.await(run));
            }
            return result;
        }

        @Override
        public void cancel() {
            run.cancel();
        }
    }

    /**
     * 把内核委派结果翻成 api 结果。
     *
     * @param runId   run 标识，可为 {@code null}（未开始）
     * @param outcome 内核结果，不可为 {@code null}
     * @return api 结果，保证非 {@code null}
     */
    private static DelegationResult toResult(String runId, SubAgentOutcome outcome) {
        // 归档路径对所有终态都补上：失败与取消的 run 同样有归档（收尾的 finally 覆盖四个出口），
        // 而「它为什么失败」的完整过程恰恰是那时最该能回看的东西
        return baseResult(runId, outcome).withArchivePath(outcome.getArchivePath());
    }

    /**
     * 把内核委派结果翻成 api 结果（不含归档路径）。
     *
     * @param runId   run 标识，可为 {@code null}（未开始）
     * @param outcome 内核结果，不可为 {@code null}
     * @return api 结果，保证非 {@code null}
     */
    private static DelegationResult baseResult(String runId, SubAgentOutcome outcome) {
        long tokens = outcome.getUsage().getTotalTokens();
        switch (outcome.getStatus()) {
            case COMPLETED:
                return DelegationResult.completed(runId, outcome.getText(), outcome.getRounds(), tokens);
            case TRUNCATED:
                return DelegationResult.truncated(runId, outcome.getText(), outcome.getRounds(), tokens);
            case CANCELLED:
                return DelegationResult.cancelled(runId, outcome.getRounds(), tokens);
            case REJECTED:
                // 被拒 = 从未开始：runId 必然是空的，交给工厂归一（调用方据此判断「重试有没有意义」）
                return DelegationResult.rejected(outcome.getError());
            default:
                return DelegationResult.failed(runId, outcome.getError());
        }
    }
}
