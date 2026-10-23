package io.github.zcd0831.jellyfish.domain.planning.plan;

import java.util.List;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/** 计划聚合根。规则出处：模块-规划 §7.1。 */
public class Plan {

    private PlanId id;
    private SessionId sessionId;
    private String goal;
    private PlanStatus status;
    private int replanGeneration;
    private List<PlanStep> steps;
}
