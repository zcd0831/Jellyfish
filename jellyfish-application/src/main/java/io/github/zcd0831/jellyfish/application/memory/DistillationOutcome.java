package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次归纳的产物：经验与技能**至多一边非空**（同一件事只进一边）。
 * 技能侧允许 0..N 条：本次用过的技能可能各自需要改进。
 * 规则出处：模块-记忆 §5、§8.6。
 */
public final class DistillationOutcome {

    private final ExperienceDraft experience;
    private final List<SkillDraft> skills;

    public DistillationOutcome(ExperienceDraft experience, List<SkillDraft> skills) {
        List<SkillDraft> copy = new ArrayList<SkillDraft>(
                skills == null ? Collections.<SkillDraft>emptyList() : skills);
        if (experience != null && !copy.isEmpty()) {
            throw new DomainInvariantViolation("经验与技能不能同时产出（同一件事只进一边）");
        }
        this.experience = experience;
        this.skills = Collections.unmodifiableList(copy);
    }

    public static DistillationOutcome empty() {
        return new DistillationOutcome(null, null);
    }

    public static DistillationOutcome experience(ExperienceDraft draft) {
        return new DistillationOutcome(draft, null);
    }

    public static DistillationOutcome skills(List<SkillDraft> drafts) {
        return new DistillationOutcome(null, drafts);
    }

    public boolean isEmpty() {
        return experience == null && skills.isEmpty();
    }

    public ExperienceDraft experience() {
        return experience;
    }

    public List<SkillDraft> skills() {
        return skills;
    }
}
