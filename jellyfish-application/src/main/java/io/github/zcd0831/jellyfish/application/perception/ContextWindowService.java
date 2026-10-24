package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
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
        if (window.getUsage().getUsedTokens() > window.getUsage().getInputCeiling()) {
            collapse(session, window);
        }
    }

    /** 承接"请求超限"后的补充折叠：不消耗重试名额（§8.2 要点 8）。 */
    public void forceCollapse(Session session) {
        collapse(session, currentWindow(session));
    }

    private void collapse(Session session, ContextWindow window) {
        List<Record> outside = recordsOutside(window, session);
        if (outside.isEmpty()) {
            return;
        }
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.SUMMARIZING, "上下文压缩中"));
        TechnicalLog.fold(session.id().getValue(), window.getUsage().getUsedTokens());

        ModelInvocationResult result = model.invoke(new ModelInvocationRequest(session.activeModelId(),
                SUMMARY_INSTRUCTION,
                Collections.singletonList(ChatMessage.of(RecordRole.USER, join(outside))),
                Collections.emptyList(), 0, null));
        if (result.usage() != null) {
            budgetService.recordUsage(session, result.usage().total());
        }

        session.markSummarized(result.text() == null ? "" : result.text(), System.currentTimeMillis());
        commitScope.stageAppend(session.id(), lastRecord(session));
        commitScope.stageReplace(session);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(session.pullEvents()));
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.SUMMARY_DONE, "上下文已压缩"));
    }

    /** 待摘要区间：不进窗口、且不是 SYSTEM / SUMMARY 的旧记录。 */
    private static List<Record> recordsOutside(ContextWindow window, Session session) {
        List<Record> inWindow = window.getRecords();
        List<Record> outside = new ArrayList<Record>();
        for (Record record : session.records()) {
            if (record.role() == RecordRole.SYSTEM || record.role() == RecordRole.SUMMARY) {
                continue;
            }
            if (!inWindow.contains(record)) {
                outside.add(record);
            }
        }
        return outside;
    }

    private static Record lastRecord(Session session) {
        List<Record> records = session.records();
        return records.get(records.size() - 1);
    }

    private static String join(List<Record> records) {
        StringBuilder out = new StringBuilder();
        for (Record record : records) {
            out.append(record.role().name()).append(": ").append(record.content()).append('\n');
        }
        return out.toString();
    }

    /** 供 ReActLoop 复用同一套上限计算，避免两处各算一遍。 */
    public ContextUsage usageOf(ContextWindow window) {
        return window.getUsage();
    }
}
