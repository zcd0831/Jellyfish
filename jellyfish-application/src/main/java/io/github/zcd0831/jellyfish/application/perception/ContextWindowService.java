package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextUsage;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindow;
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
 * 规则出处：04-perception §4.9.2、§4.9.5。
 */
public final class ContextWindowService {

    /** 摘要提示词用户不可见、且不进 Record（04-perception §4.8.1 规则 19）。 */
    private static final String SUMMARY_INSTRUCTION =
            "把上面的对话压缩成要点，必须保留：用户的目标与约束、已确认的事实、已完成的步骤及其结论"
                    + "（含失败原因）、已做过的操作与结果、待办、已加载技能与经验的关键约束。"
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

    /** 当前窗口（每次推导，不落库）。规则出处：04-perception §4.8.1 规则 9/11。 */
    public ContextWindow currentWindow(Session session) {
        return session.buildContextForLlm(estimator, inputCeiling(policies.load(), session));
    }

    /** 有效上限 = min(策略阈值, maxInputTokens − 安全余量)。规则出处：04-perception §4.9.2 要点 8。 */
    public int inputCeiling(RuntimePolicy policy, Session session) {
        int maxInput = model.describe(session.activeModelId()).maxInputTokens();
        return Math.min(policy.summarizationTokenThreshold(), maxInput - policy.inputSafetyMargin());
    }

    public void ensureWithinLimit(Session session) {
        ContextWindow window = currentWindow(session);
        announceContextUsage(session);
        if (window.getUsage().getUsedTokens() + estimator.estimate(SUMMARY_INSTRUCTION)
                <= window.getUsage().getInputCeiling()) {
            return;
        }
        collapse(session, policies.load(), window);
        budgetService.forgetPromptObservation(session);
        announceContextUsage(session);
    }

    /**
     * 请求用量 / 模型输入上限：界面常驻展示用。
     * 数值取 Budget 里"最近一次请求"的观测（厂商精确值，估算加 ≈）；没有就显示 —（新会话、刚折叠）。
     * 折叠判定仍用请求前的估算（window），两者不混用。规则出处：08-application §8.7。
     */
    private void announceContextUsage(Session session) {
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.CONTEXT_USAGE, contextLabel(session)));
    }

    /** 主循环请求结束后：记账 + 记下观测 + 立刻把标签推给界面（不必等下一轮）。 */
    public boolean recordTurnUsage(Session session, io.github.zcd0831.jellyfish.application.action.port.ModelUsage usage) {
        boolean warned = budgetService.recordTurnUsage(session, usage);
        announceContextUsage(session);
        return warned;
    }

    /** 界面标签：最近一次请求的用量 / 模型输入上限（回放历史会话时也用它）。 */
    public String contextLabel(Session session) {
        int maxInput = model.describe(session.activeModelId()).maxInputTokens();
        Budget budget = budgetService.budgetOf(session);
        String value = "—";
        if (budget != null && session.id().getValue().equals(budget.lastPromptSessionId())
                && budget.lastPromptTokens() > 0) {
            value = (budget.lastPromptEstimated() ? "≈" : "") + tokens((int) budget.lastPromptTokens());
        }
        return value + " / " + tokens(maxInput);
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

    /** 承接"请求超限"后的补充折叠：不消耗重试名额（04-perception §4.9.2 要点 10）。 */
    public void forceCollapse(Session session) {
        collapse(session, policies.load(), currentWindow(session));
        budgetService.forgetPromptObservation(session);
        announceContextUsage(session);
    }

    /**
     * 折叠 = 把窗口收回到配置条数：保留区 = 覆盖边界之后最近的整轮、累计条数 ≥ retainRecordCount；
     * 待摘要区间 = 旧摘要 + 覆盖边界之后到保留区之前的全部记录。
     * 摘要请求是“影子主请求”：同快照、同 tools、当前窗口 + 末尾指令，指令不落 Record。
     * 规则出处：04-perception §4.9.2、§4.8.1 规则 19/20/21。
     */
    private void collapse(Session session, RuntimePolicy policy, ContextWindow window) {
        List<List<Record>> turns = session.turnsAfter(coveredThrough(session));
        int keepFrom = retainStart(turns, policy.collapseRetainRecordCount());
        if (keepFrom <= 0) {
            return;   // 窗口本来就在保留区以内：没有可折的旧轮
        }
        int covered = lastSequenceOf(turns.subList(0, keepFrom));
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.SUMMARIZING, "上下文压缩中"));
        TechnicalLog.fold(session.id().getValue(), window.getUsage().getUsedTokens());

        List<Record> material = new ArrayList<Record>(summaryOf(window));
        List<List<Record>> kept = new ArrayList<List<Record>>(turns.subList(0, keepFrom));
        List<Record> dropped = new ArrayList<Record>();
        ModelInvocationResult result = summarize(session, material, kept, dropped);
        if (result == null) {
            return;   // 素材丢完仍超限：本次不折叠，留给上层按“请求超限”处理
        }
        if (result.usage() != null) {
            budgetService.recordUsage(session, result.usage());
        }
        session.markSummarized(withHoleNote(result.text(), dropped), covered, System.currentTimeMillis());
        commitScope.stageAppend(session.id(), lastRecord(session));
        commitScope.stageReplace(session);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(session.pullEvents()));
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.SUMMARY_DONE, "上下文已压缩"));
    }

    /**
     * 摘要调用。输入超限 ⇒ 从最旧一整轮开始丢素材重试；模型不可用/取消 ⇒ 直接上抛（不落半截摘要）。
     * 规则出处：04-perception §4.8.1 规则 21。
     */
    private ModelInvocationResult summarize(Session session, List<Record> material,
                                            List<List<Record>> kept, List<Record> dropped) {
        while (true) {
            List<Record> records = new ArrayList<Record>(material);
            for (List<Record> turn : kept) {
                records.addAll(turn);
            }
            List<ChatMessage> messages = ConversationMessages.of(records, session.activeModelId());
            messages.add(ChatMessage.of(RecordRole.USER, SUMMARY_INSTRUCTION));
            try {
                return model.invoke(shadowRequest(session, messages));
            } catch (ModelInvocationException exceeded) {
                if (exceeded.error() != ModelError.CONTEXT_EXCEEDED || kept.isEmpty()) {
                    throw exceeded;
                }
                dropped.addAll(kept.remove(0));
            }
        }
    }

    /** 影子主请求：同一快照与 tools 快照，才能命中主请求写下的缓存前缀。 */
    private static ModelInvocationRequest shadowRequest(Session session, List<ChatMessage> messages) {
        String systemPrompt = session.systemPromptSnapshot() == null
                ? "" : session.systemPromptSnapshot().getRenderedPrompt();
        List<io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot> tools =
                session.systemPromptSnapshot() == null
                        ? Collections.<io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot>emptyList()
                        : session.systemPromptSnapshot().getToolList();
        return new ModelInvocationRequest(session.id(), session.activeModelId(), systemPrompt, messages, tools, 0, null);
    }

    /** 保留区从第几轮开始：从最后一轮往前累计条数，够 retainCount 即止（整轮对齐）。 */
    private static int retainStart(List<List<Record>> turns, int retainCount) {
        int count = 0;
        int index = turns.size();
        while (index > 0 && count < retainCount) {
            index--;
            count += turns.get(index).size();
        }
        return index;
    }

    /** 旧摘要作为首条素材：新摘要要吞并它，否则第二次折叠会丢掉第一次的事实。 */
    private static List<Record> summaryOf(ContextWindow window) {
        if (window.getRecords().isEmpty()) {
            return Collections.emptyList();
        }
        Record first = window.getRecords().get(0);
        return first.role() == RecordRole.SUMMARY
                ? Collections.singletonList(first) : Collections.<Record>emptyList();
    }

    /** 被丢弃的区间必须在摘要正文里留痕，否则等于静默丢历史。 */
    private static String withHoleNote(String summary, List<Record> dropped) {
        if (dropped.isEmpty()) {
            return summary == null ? "" : summary;
        }
        return "（更早的 " + dropped.size() + " 条记录 seq " + dropped.get(0).sequence() + ".."
                + dropped.get(dropped.size() - 1).sequence() + " 因超出输入上限未纳入摘要）\n"
                + (summary == null ? "" : summary);
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

    /** 供 ReActLoop 复用同一套上限计算，避免两处各算一遍。 */
    public ContextUsage usageOf(ContextWindow window) {
        return window.getUsage();
    }
}
