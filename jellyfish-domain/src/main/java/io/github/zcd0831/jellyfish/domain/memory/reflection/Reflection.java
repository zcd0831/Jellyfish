package io.github.zcd0831.jellyfish.domain.memory.reflection;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次失败的反思结论：不可变，归属唯一 `(planId, stepId, attemptNo)`。
 * 双消费者：回注下一次 attempt 的失败原因 + 经验沉淀素材。
 * 规则出处：模块-记忆 §3.2、§7.2。
 */
public final class Reflection {

    private final ReflectionId id;
    private final PlanId planId;
    private final StepId stepId;
    private final int attemptNo;
    private final String conclusion;
    private final long createdAt;
    private final List<DomainEvent> pendingEvents = new ArrayList<DomainEvent>();

    private Reflection(ReflectionId id, PlanId planId, StepId stepId, int attemptNo, String conclusion,
                       long createdAt) {
        this.id = id;
        this.planId = planId;
        this.stepId = stepId;
        this.attemptNo = attemptNo;
        this.conclusion = conclusion;
        this.createdAt = createdAt;
    }

    /** 归属唯一由 ID 保证：同一个三元组永远得到同一个 reflectionId（§7.2 规则 ⑤）。 */
    public static ReflectionId identify(PlanId planId, StepId stepId, int attemptNo) {
        String key = planId.getValue() + "/" + stepId.getValue() + "/" + attemptNo;
        return ReflectionId.of(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
    }

    /** 规则出处：§6（落库即 raise ReflectionRecorded）。 */
    public static Reflection record(PlanId planId, StepId stepId, int attemptNo, String conclusion, long now) {
        Reflection reflection = new Reflection(identify(planId, stepId, attemptNo), planId, stepId, attemptNo,
                conclusion, now);
        reflection.pendingEvents.add(new ReflectionRecorded());
        return reflection;
    }

    /** 续跑重建：不重放事件。 */
    public static Reflection rehydrate(ReflectionId id, PlanId planId, StepId stepId, int attemptNo,
                                       String conclusion, long createdAt) {
        return new Reflection(id, planId, stepId, attemptNo, conclusion, createdAt);
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<DomainEvent>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public ReflectionId id() {
        return id;
    }

    public PlanId planId() {
        return planId;
    }

    public StepId stepId() {
        return stepId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public String conclusion() {
        return conclusion;
    }

    public long createdAt() {
        return createdAt;
    }
}
