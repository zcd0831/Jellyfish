package io.github.zcd0831.jellyfish.domain.memory.skill;

import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技能聚合：可变性守护与四件套（模块-记忆 §7.3）。 */
class SkillDefinitionTest {

    @Test
    void 沉淀出来即活跃且可变() {
        SkillDefinition skill = SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", "正文");

        assertTrue(skill.isActive());
        assertTrue(skill.isMutable());
        assertEquals(SkillStatus.ACTIVE, skill.status());
    }

    @Test
    void 不可变技能拒绝改写() {
        SkillDefinition locked = new SkillDefinition(SkillId.of("builtin.x"), "名", "述", "当…时",
                new InstructionTemplate("旧"), SkillStatus.ACTIVE, false);

        assertThrows(DomainInvariantViolation.class,
                () -> locked.revise("新名", "新述", "新触发", "新正文"));
        assertEquals("旧", locked.sopTemplate().getRawMarkdown(), "拒绝后原正文不变");
    }

    @Test
    void 可变技能整篇覆盖() {
        SkillDefinition skill = SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", "旧");

        skill.revise("新名", "新述", "新触发", "新正文");

        assertEquals("新名", skill.name());
        assertEquals("新正文", skill.sopTemplate().getRawMarkdown());
    }

    @Test
    void 四件套缺一即不合法() {
        assertThrows(DomainInvariantViolation.class,
                () -> SkillDefinition.create(SkillId.of("learned.a"), "名", " ", "当…时", "正文"));
        assertThrows(DomainInvariantViolation.class,
                () -> SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", ""));
    }

    @Test
    void 新建与改写各raise一个事件() {
        SkillDefinition skill = SkillDefinition.create(SkillId.of("learned.a"), "名", "述", "当…时", "旧");
        skill.revise("名", "述", "当…时", "新");

        assertEquals(2, skill.pullEvents().size());
        assertTrue(skill.pullEvents().isEmpty(), "取走即清空");
    }
}
