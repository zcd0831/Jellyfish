package io.github.zcd0831.jellyfish.application.planning.port;

import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import java.util.List;

/**
 * 计划聚合。规则出处：09-infrastructure §9.4。
 */
public interface PlanRepository {

    Plan load(PlanId id);

    void save(Plan plan);

    List<Plan> findRunning(SessionId sessionId);
}
