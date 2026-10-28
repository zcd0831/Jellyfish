package io.github.zcd0831.jellyfish.domain.memory.skill;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.util.ArrayList;
import java.util.List;

/**
 * 程序性知识（SOP）：人写与机学同构，唯一差别是 {@code mutable}（谁有权改写正文）。
 * 规则出处：07-memory §7.4.3、§7.8.3。
 */
public class SkillDefinition {

    private final SkillId id;
    private final List<DomainEvent> pendingEvents = new ArrayList<DomainEvent>();

    private String name;
    private String description;
    private String triggerCondition;
    private InstructionTemplate sopTemplate;
    private SkillStatus status;
    private boolean mutable;
    /** 写入侧幂等键：这份正文由哪些计划改写而来（09-infrastructure §9.5 的 front-matter 字段）。 */
    private final List<PlanId> sourcePlanIds;

    public SkillDefinition(SkillId id, String name, String description, String triggerCondition,
                           InstructionTemplate sopTemplate, SkillStatus status, boolean mutable,
                           List<PlanId> sourcePlanIds) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.triggerCondition = triggerCondition;
        this.sopTemplate = sopTemplate;
        this.status = status;
        this.mutable = mutable;
        this.sourcePlanIds = new ArrayList<PlanId>(
                sourcePlanIds == null ? java.util.Collections.<PlanId>emptyList() : sourcePlanIds);
    }

    /** 机学沉淀：写出即 ACTIVE、即可用（§7.3 规则 ④）。 */
    public static SkillDefinition create(SkillId id, String name, String description, String triggerCondition,
                                         String rawMarkdown, PlanId sourcePlanId) {
        SkillDefinition skill = new SkillDefinition(id, name, description, triggerCondition,
                new InstructionTemplate(rawMarkdown), SkillStatus.ACTIVE, true,
                sourcePlanId == null ? null : java.util.Collections.singletonList(sourcePlanId));
        skill.requireComplete();
        skill.pendingEvents.add(new SkillLearned());
        return skill;
    }

    /**
     * 改进：整篇覆盖，不留版本链（07-memory §7.8.3 规则 5）。
     * 不可变技能必须在这里挡住——可变性由聚合守护，不能靠调用方自觉（07-memory §7.8.3 规则 3）。
     */
    public void revise(String name, String description, String triggerCondition, String rawMarkdown,
                       PlanId sourcePlanId) {
        if (!mutable) {
            throw new DomainInvariantViolation("技能声明为不可变，系统无权改写：" + id.getValue());
        }
        this.name = name;
        this.description = description;
        this.triggerCondition = triggerCondition;
        this.sopTemplate = new InstructionTemplate(rawMarkdown);
        if (sourcePlanId != null && !sourcePlanIds.contains(sourcePlanId)) {
            sourcePlanIds.add(sourcePlanId);
        }
        requireComplete();
        pendingEvents.add(new SkillRevised());
    }

    /** 只有 ACTIVE 进入会话快照（§7.3 规则 ⑧）。 */
    /** 幂等：同一个计划已经改写过就不重复改写（07-memory §7.8.1 规则 12）。 */
    public boolean sourcedFrom(PlanId planId) {
        return planId != null && sourcePlanIds.contains(planId);
    }

    public List<PlanId> sourcePlanIds() {
        return java.util.Collections.unmodifiableList(sourcePlanIds);
    }

    public boolean isActive() {
        return status == SkillStatus.ACTIVE;
    }

    public boolean isMutable() {
        return mutable;
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<DomainEvent>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    /** 四件套非空：描述写坏了技能就不可发现（§7.3 不变量 ④）。 */
    private void requireComplete() {
        requireText(name, "name");
        requireText(description, "description");
        requireText(triggerCondition, "triggerCondition");
        requireText(sopTemplate == null ? null : sopTemplate.getRawMarkdown(), "sopBody");
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new DomainInvariantViolation("技能 " + id.getValue() + " 缺少 " + field);
        }
    }

    public SkillId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String triggerCondition() {
        return triggerCondition;
    }

    public InstructionTemplate sopTemplate() {
        return sopTemplate;
    }

    public SkillStatus status() {
        return status;
    }
}
