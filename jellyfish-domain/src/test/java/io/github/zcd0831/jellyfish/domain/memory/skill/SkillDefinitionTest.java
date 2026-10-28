package io.github.zcd0831.jellyfish.domain.memory.skill;

import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技能聚合规则。规则出处：07-memory §7.8.3、§7.4.3。 */
class SkillDefinitionTest {

    @Test
    void 沉淀出来即活跃且可变() {
        SkillDefinition skill = SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", "正文", null);

        assertTrue(skill.isActive());
        assertTrue(skill.isMutable());
        assertEquals(SkillStatus.ACTIVE, skill.status());
    }

    @Test
    void 不可变技能拒绝改写() {
        SkillDefinition locked = new SkillDefinition(SkillId.of("builtin.x"), "名", "述", "当…时",
                new InstructionTemplate("旧"), SkillStatus.ACTIVE, false, null);

        assertThrows(DomainInvariantViolation.class,
                () -> locked.revise("新名", "新述", "新触发", "新正文", null));
        assertEquals("旧", locked.sopTemplate().getRawMarkdown(), "拒绝后原正文不变");
    }

    @Test
    void 可变技能整篇覆盖() {
        SkillDefinition skill = SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", "旧", null);

        skill.revise("新名", "新述", "新触发", "新正文", null);

        assertEquals("新名", skill.name());
        assertEquals("新正文", skill.sopTemplate().getRawMarkdown());
    }

    @Test
    void 四件套缺一即不合法() {
        assertThrows(DomainInvariantViolation.class,
                () -> SkillDefinition.create(SkillId.of("learned.a"), "名", " ", "当…时", "正文", null));
        assertThrows(DomainInvariantViolation.class,
                () -> SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", "", null));
    }

    @Test
    void 新建与改写各raise一个事件() {
        SkillDefinition skill = SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", "旧", null);
        skill.revise("名", "述", "当…时", "新", null);

        assertEquals(2, skill.pullEvents().size());
        assertTrue(skill.pullEvents().isEmpty(), "取走即清空");
    }

    @Test
    void 来源计划用来做写入侧幂等() {
        PlanId planId = PlanId.of("p1");
        SkillDefinition skill = SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", "正文", planId);

        assertTrue(skill.sourcedFrom(planId));
        assertFalse(skill.sourcedFrom(PlanId.of("p2")));
        assertEquals(Collections.singletonList(planId), skill.sourcePlanIds());

        skill.revise("名", "述", "当…时", "新正文", PlanId.of("p2"));

        assertEquals(2, skill.sourcePlanIds().size(), "改写也要累加来源计划");
    }
}
