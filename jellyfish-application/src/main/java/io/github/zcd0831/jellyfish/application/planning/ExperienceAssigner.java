package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.RetrievalInput;
import io.github.zcd0831.jellyfish.application.memory.StepBrief;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计划诞生与重划时的经验分配：模型只负责拆步骤，经验由这里按 step 检索并交给聚合自己改状态。
 * <p>两个计划处理器共用它。**必须与计划落盘同一次提交**——把它推到外循环去，就在
 * "计划已落盘、经验还没分"之间开了个窗口：同一次响应里再出错或被终止，新计划永远
 * 拿不到经验，之后也没有任何路径会补（06-planning §6.8.1 规则 6、§6.9.4、07-memory §7.9.1）。
 */
public final class ExperienceAssigner {

    private final ExperienceRetriever retriever;

    public ExperienceAssigner(ExperienceRetriever retriever) {
        this.retriever = retriever;
    }

    /** 只给还没开始的步骤分配：已执行过的 attempt 引用的经验不该被改写。 */
    public void assign(Session session, Plan plan) {
        List<StepBrief> briefs = new ArrayList<StepBrief>();
        for (PlanStep step : plan.steps()) {
            if (step.status() == StepStatus.PENDING) {
                briefs.add(new StepBrief(step.id(), step.description()));
            }
        }
        if (briefs.isEmpty()) {
            return;
        }
        Map<StepId, List<ExperienceId>> assignment =
                retriever.retrieveAndAssign(session, new RetrievalInput(plan.id(), briefs));
        for (Map.Entry<StepId, List<ExperienceId>> entry : assignment.entrySet()) {
            plan.assignExperiences(entry.getKey(), entry.getValue());
        }
    }
}
