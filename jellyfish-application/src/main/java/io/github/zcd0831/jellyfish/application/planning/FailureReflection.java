package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;

/**
 * 失败反思：达阈值时跑内置反思子代理（按文档是子代理，不是进程内调用），结论落 Reflection 并挂回 attempt。
 * <p>**只 stage、不提交**——事实与随后的重试/纠偏/终止必须是同一次状态转移（08-application §8.8.2 T7）。
 * 规则出处：07-memory §7.8.2、§7.9.1 回注、08-application §8.9.7。
 */
final class FailureReflection {

    private final AgentId reflectAgent = AgentId.of("builtin.reflect");
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final InternalSessionRunner forks;

    FailureReflection(CatalogRepository catalog, PolicyRepository policies, CommitScope commitScope,
                      InternalSessionRunner forks) {
        this.catalog = catalog;
        this.policies = policies;
        this.commitScope = commitScope;
        this.forks = forks;
    }

    /** 没达阈值、代理缺失、调用失败都返回 null（"这次不反思"），由调用方照常推进。 */
    Reflection reflectIfNeeded(Session session, Plan plan, PlanStep step, String failureReason) {
        int failed = PlanProgress.failedAttempts(step);
        if (!policies.load().needsReflection(failed)) {
            return null;
        }
        String conclusion = reflect(session, step, failureReason);
        if (conclusion == null) {
            return null;
        }
        Reflection reflection = Reflection.record(plan.id(), step.id(), failedIn(step), conclusion,
                System.currentTimeMillis());
        commitScope.stageFact(reflection);
        plan.recordReflection(step.id(), reflection.attemptNo(), reflection.id());
        return reflection;
    }

    /**
     * 反思 = fork 当前生效窗口 + 末尾“反思指令 + 失败原因”：轨迹从窗口里来，不再单独拼素材。
     * 代理缺失或调用失败 ⇒ 本次不反思：把错误文案存成反思结论比不反思更糟；
     * 但**终止要上抛**，否则取消被吞后仍会走重试/重划（08-application §8.9.4.1）。
     */
    private String reflect(Session session, PlanStep step, String failureReason) {
        AgentDefinition agent = agentOf();
        if (agent == null) {
            return null;
        }
        String instruction = agent.basePrompt().getTemplateText()
                + "\n\n原定目标：" + step.description() + "\n失败原因：" + failureReason
                + "\n请给出下一次该改什么。";
        try {
            String conclusion = forks.run(session, instruction);
            return conclusion == null || conclusion.trim().isEmpty() ? null : conclusion;
        } catch (java.util.concurrent.CancellationException terminated) {
            // 终止是终态：不当成“本次不反思”，也不再推进状态（08-application §8.9.4.1、§8.9.7）
            throw terminated;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private AgentDefinition agentOf() {
        for (AgentDefinition agent : catalog.agents()) {
            if (agent.id().equals(reflectAgent) && agent.isActive()) {
                return agent;
            }
        }
        return null;
    }

    private static int failedIn(PlanStep step) {
        Attempt latest = step.attempts().get(step.attempts().size() - 1);
        return latest.attemptNo();
    }
}
