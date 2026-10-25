package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextUsage;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindow;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindowPolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 计量 → 算有效上限 → 超限则折叠（调模型生成摘要）→ 追加 SUMMARY。
 * 规则出处：模块-感知 §4、§8.2；技术决策 D08。
 */
public final class ContextWindowService {

    /** 摘要提示词用户不可见、且不进 Record（§8.2 要点 2）。 */
    private static final String SUMMARY_INSTRUCTION =
            "把下面的对话压缩成要点，保留：用户的目标、已确认的事实、已做过的操作与结果、待办。"
                    + "不要编造，不要客套，直接输出要点列表。";

    private final ModelInvocationPort model;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ProgressPort progress;
    private final BudgetService budgetService;
    private final TokenEstimator estimator;

    public ContextWindowService(ModelInvocationPort model, PolicyRepository policies, CommitScope commitScope,
                                DomainEventPublisher eventPublisher, ProgressPort progress,
                                BudgetService budgetService, TokenEstimator estimator) {
        this.model = model;
        this.policies = policies;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.progress = progress;
        this.budgetService = budgetService;
        this.estimator = estimator;
    }

    /** 当前窗口（每次推导，不落库）。规则出处：模块-感知 §7.1 规则 9、11。 */
    public ContextWindow currentWindow(Session session) {
        RuntimePolicy policy = policies.load();
        int ceiling = inputCeiling(policy, session);
        return session.buildContextForLlm(
                new ContextWindowPolicy(policy.contextWindowRecordCount(), policy.systemRecordRatioLimit()),
                estimator, ceiling);
    }

    /** 有效上限 = min(策略阈值, maxInputTokens − 安全余量)。规则出处：§8.2 要点 5。 */
    public int inputCeiling(RuntimePolicy policy, Session session) {
        int maxInput = model.describe(session.activeModelId()).maxInputTokens();
        return Math.min(policy.summarizationTokenThreshold(), maxInput - policy.inputSafetyMargin());
    }

    public void ensureWithinLimit(Session session) {
        ContextWindow window = currentWindow(session);
        announceContextUsage(session, window);
        if (window.getUsage().getUsedTokens() > window.getUsage().getInputCeiling()) {
            collapse(session, window);
            announceContextUsage(session, currentWindow(session));
        }
    }

    /** 上下文占用 / 模型输入上限：界面常驻展示用（可丢弃，不影响折叠判定）。 */
    private void announceContextUsage(Session session, ContextWindow window) {
        int maxInput = model.describe(session.activeModelId()).maxInputTokens();
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.CONTEXT_USAGE,
                "上下文 " + tokens(window.getUsage().getUsedTokens()) + " / " + tokens(maxInput)));
    }

    /** 1.2k / 950 这类短写法：界面上只求一眼可读。 */
    static String tokens(int value) {
        if (value < 1000) {
            return String.valueOf(value);
        }
        if (value % 1000 == 0) {
            return (value / 1000) + "k";
        }
        return String.format(java.util.Locale.ROOT, "%.1fk", value / 1000.0);
    }

    /** 承接"请求超限"后的补充折叠：不消耗重试名额（§8.2 要点 8）。 */
    public void forceCollapse(Session session) {
        collapse(session, currentWindow(session));
    }

    /**
     * 折叠 = 把窗口变短：从未被摘要覆盖的记录里，按"模型输出轮"从最旧一条开始整轮摘掉，
     * 直到移除的量够把用量压回上限以内（至少一轮、且至少留一轮）。
     * 规则出处：模块-感知 §7.1 规则 9 ③ / 规则 14、§8.2 要点 4。
     */
    private void collapse(Session session, ContextWindow window) {
        List<List<Record>> turns = session.turnsAfter(coveredThrough(session));
        if (turns.size() < 2) {
            return;   // 没有可折的旧轮，或只剩一轮：折了就等于把当前对话全丢掉
        }
        int used = window.getUsage().getUsedTokens();
        int ceiling = window.getUsage().getInputCeiling();
        int need = Math.max(0, used - ceiling);

        int removed = 0;
        int foldCount = 0;
        while (foldCount < turns.size() - 1) {
            removed += tokensOf(turns.get(foldCount));
            foldCount++;
            if (removed >= need) {
                break;
            }
        }
        if (foldCount == 0) {
            return;
        }
        List<List<Record>> folded = turns.subList(0, foldCount);
        int covered = lastSequenceOf(folded);
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.SUMMARIZING, "上下文压缩中"));
        TechnicalLog.fold(session.id().getValue(), used);

        ModelInvocationResult result = model.invoke(new ModelInvocationRequest(session.activeModelId(),
                SUMMARY_INSTRUCTION,
                Collections.singletonList(ChatMessage.of(RecordRole.USER, join(folded))),
                Collections.emptyList(), 0, null));
        if (result.usage() != null) {
            budgetService.recordUsage(session, result.usage().total());
        }

        session.markSummarized(result.text() == null ? "" : result.text(), covered,
                System.currentTimeMillis());
        commitScope.stageAppend(session.id(), lastRecord(session));
        commitScope.stageReplace(session);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(session.pullEvents()));
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.SUMMARY_DONE, "上下文已压缩"));
    }

    /** 最新摘要覆盖到哪条记录；没有摘要即 0（规则 9 ③）。 */
    private static int coveredThrough(Session session) {
        int covered = 0;
        for (Record record : session.records()) {
            if (record.role() == RecordRole.SUMMARY) {
                covered = record.summaryCoversUpTo();
            }
        }
        return covered;
    }

    private static int lastSequenceOf(List<List<Record>> turns) {
        List<Record> last = turns.get(turns.size() - 1);
        return last.get(last.size() - 1).sequence();
    }

    private static Record lastRecord(Session session) {
        List<Record> records = session.records();
        return records.get(records.size() - 1);
    }

    /** 摘要素材只汇总可见内容与工具结果；reasoning 不进摘要（§7.1 规则 15）。 */
    private static String join(List<List<Record>> turns) {
        StringBuilder out = new StringBuilder();
        for (List<Record> turn : turns) {
            for (Record record : turn) {
                out.append(record.role().name()).append(": ").append(record.content()).append('\n');
            }
        }
        return out.toString();
    }

    private int tokensOf(List<Record> records) {
        int total = 0;
        for (Record record : records) {
            if (record.content() != null) {
                total += estimator.estimate(record.content());
            }
            if (record.reasoning() != null) {
                total += estimator.estimate(record.reasoning());
            }
        }
        return total;
    }

    /** 供 ReActLoop 复用同一套上限计算，避免两处各算一遍。 */
    public ContextUsage usageOf(ContextWindow window) {
        return window.getUsage();
    }
}
