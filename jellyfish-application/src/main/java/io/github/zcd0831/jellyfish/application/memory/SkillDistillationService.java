package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 技能写入：把子代理的草稿落成聚合变更，并原子重写 SKILL.md。
 * 零计数、零质量分、零阈值——"要不要改"由子代理判定，这里只守规则。
 * 规则出处：07-memory §7.8.3、§7.9.5。
 */
public final class SkillDistillationService {

    private static final String LEARNED_PREFIX = "learned.";

    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ProgressPort progress;

    public SkillDistillationService(CatalogRepository catalog, PolicyRepository policies,
                                    CommitScope commitScope, DomainEventPublisher eventPublisher,
                                    ProgressPort progress) {
        this.catalog = catalog;
        this.policies = policies;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.progress = progress;
    }

    public void apply(final SkillDraft draft, SessionId sessionId, final io.github.zcd0831.jellyfish.domain.shared.PlanId planId) {
        if (draft == null || !draft.isComplete() || alreadySourced(planId)) {
            return;   // 四件套不全：本次沉淀失败，不发事件、不占额度
        }
        // 容量"读—判—写"必须在全局写段内完成（07-memory §7.9.5）
        commitScope.globalWrite(new Runnable() {
            @Override
            public void run() {
                applyLocked(draft, planId);
            }
        });
    }

    private void applyLocked(SkillDraft draft, io.github.zcd0831.jellyfish.domain.shared.PlanId planId) {
        SkillDefinition target = draft.isMerge() ? find(draft.targetSkillId()) : null;
        if (draft.isMerge() && target == null) {
            return;   // target 不存在/非法：子代理幻觉，整次作废，不奖励新建
        }
        if (target != null && (!target.isActive() || !target.isMutable())) {
            target = null;   // 不可改或已下线：降级 CREATE（正常冲突，人写技能不许改）
        }
        if (target != null) {
            target.revise(draft.name(), draft.description(), draft.triggerCondition(), draft.sopBody(), planId);
            save(target);
            return;
        }
        if (catalog.countActiveSkills() >= policies.load().skillCapacity()) {
            // 到限不静默：否则用户会以为系统还在学
            progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                    "技能数已达上限 " + policies.load().skillCapacity() + "，本次未沉淀新技能"));
            return;
        }
        save(SkillDefinition.create(newSkillId(draft), draft.name(), draft.description(),
                draft.triggerCondition(), draft.sopBody(), planId));
    }

    /** 幂等：同一个计划已经改写过技能就整段跳过（07-memory §7.8.1 规则 12）。 */
    private boolean alreadySourced(io.github.zcd0831.jellyfish.domain.shared.PlanId planId) {
        if (planId == null) {
            return false;
        }
        for (SkillDefinition skill : catalog.skills()) {
            if (skill.sourcedFrom(planId)) {
                return true;
            }
        }
        return false;
    }

    private SkillDefinition find(SkillId skillId) {
        if (skillId == null) {
            return null;
        }
        for (SkillDefinition skill : catalog.skills()) {
            if (skill.id().equals(skillId)) {
                return skill;
            }
        }
        return null;
    }

    /** `learned.<slug>`：可读的稳定 id；同名已在则带短后缀，绝不静默覆盖。 */
    private SkillId newSkillId(SkillDraft draft) {
        String slug = slugOf(draft.name());
        SkillId candidate = SkillId.of(LEARNED_PREFIX + slug);
        if (find(candidate) == null) {
            return candidate;
        }
        return SkillId.of(LEARNED_PREFIX + slug + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String slugOf(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        String slug = out.toString();
        while (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isEmpty() ? UUID.randomUUID().toString().substring(0, 8) : slug;
    }

    private void save(SkillDefinition skill) {
        catalog.saveSkill(skill);
        ArrayList<DomainEvent> events = new ArrayList<DomainEvent>(skill.pullEvents());
        if (!events.isEmpty()) {
            eventPublisher.publishAfterCommit(events);
        }
    }
}
