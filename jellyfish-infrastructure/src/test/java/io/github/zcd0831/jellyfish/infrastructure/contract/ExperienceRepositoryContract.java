package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.ExperienceStatus;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `ExperienceRepository` 的契约：全局库、active 过滤、归档保留。 */
public abstract class ExperienceRepositoryContract {

    protected abstract ExperienceRepository repository();

    private static Experience experience(String id, double confidence, long createdAt) {
        return Experience.create(ExperienceId.of(id), "场景" + id,
                new LessonLearned("教训" + id, "期望"), confidence, createdAt);
    }

    @Test
    void 保存后出现在active列表并可按id读回() {
        repository().save(experience("e1", 0.5, 10L));

        assertEquals(1, repository().findActive().size());
        assertTrue(repository().find(ExperienceId.of("e1")).isPresent());
        assertEquals(2, repository().count() + 1, "count 只数文件，这里至少为 1");
    }

    @Test
    void 归档后不再出现在active但文件还在() {
        repository().save(experience("e1", 0.5, 10L));

        repository().archive(ExperienceId.of("e1"));

        assertTrue(repository().findActive().isEmpty());
        assertFalse(repository().find(ExperienceId.of("e1")).get().isActive());
        assertEquals(1, repository().count(), "归档保留供审计");
    }

    @Test
    void 不存在的经验返回空() {
        assertFalse(repository().find(ExperienceId.of("nope")).isPresent());
    }

    @Test
    void 置信度与使用次数被持久化() {
        Experience experience = experience("e1", 0.5, 10L);
        experience.reinforce(0.1);
        repository().save(experience);

        Experience loaded = repository().find(ExperienceId.of("e1")).get();

        assertEquals(0.6, loaded.confidence().getValue(), 1e-9);
        assertEquals(1, loaded.appliedCount());
        assertEquals(ExperienceStatus.ACTIVE, loaded.status());
    }
}
