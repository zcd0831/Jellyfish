package io.github.zcd0831.jellyfish.domain.memory.experience;

import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 置信度演化与淘汰：初值 0.5、±0.1、低于 0.2 淘汰。
 * 规则出处：07-memory §7.4.1、§7.8.1 规则 5⑥⑧。
 */
class ExperienceTest {

    private static Experience experience() {
        LessonLearned lesson = new LessonLearned("先读文件再改", "改动前确认原文");
        return Experience.create(ExperienceId.of("e1"), "需要修改代码时", lesson, 0.5, 100L, null);
    }

    @Test
    void 新建raise事件且置信度取初值() {
        Experience experience = experience();

        assertEquals(0.5, experience.confidence().getValue(), 1e-9);
        assertEquals(ExperienceStatus.ACTIVE, experience.status());
        assertEquals("experience.created", experience.pullEvents().get(0).name());
    }

    @Test
    void 奖励提升置信度与使用次数且上限为1() {
        Experience experience = experience();
        experience.pullEvents();

        experience.reinforce(0.1);
        experience.reinforce(0.6);

        assertEquals(1.0, experience.confidence().getValue(), 1e-9);
        assertEquals(2, experience.appliedCount());
        assertEquals("experience.reinforced", experience.pullEvents().get(0).name());
    }

    @Test
    void 惩罚降低置信度跌破阈值即淘汰且下限为0() {
        Experience experience = experience();
        experience.pullEvents();

        experience.penalize(0.1, 0.2);
        assertTrue(experience.isActive());

        experience.penalize(0.3, 0.2);
        assertFalse(experience.isActive());
        assertEquals(0.1, experience.confidence().getValue(), 1e-9);
    }

    @Test
    void 置信度不能越界() {
        assertThrows(DomainInvariantViolation.class, () -> new ConfidenceScore(1.5));
        assertThrows(DomainInvariantViolation.class, () -> new ConfidenceScore(-0.1));
    }

    @Test
    void 合并时累加使用次数并提置信度() {
        Experience mine = experience();
        Experience other = Experience.create(ExperienceId.of("e2"), "同类场景",
                new LessonLearned("更清晰的表述", "期望"), 0.6, 200L, null);
        other.reinforce(0.1);

        mine.mergeWith(other, 0.1);

        assertEquals(0.6, mine.confidence().getValue(), 1e-9);
        assertEquals(1, mine.appliedCount());
    }
}
