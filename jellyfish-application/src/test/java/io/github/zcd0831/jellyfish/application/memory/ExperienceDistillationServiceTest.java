package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.memory.experience.ConfidenceScore;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.ExperienceStatus;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 经验写入的规则：**没有假模型**也能断言——这是把编排拆出去的收益（模块-记忆 §8.2）。
 */
class ExperienceDistillationServiceTest {

    private final ExperienceRepository experiences = mock(ExperienceRepository.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);

    private ExperienceDistillationService service(RuntimePolicy policy) {
        when(policies.load()).thenReturn(policy);
        return new ExperienceDistillationService(experiences, policies, commitScope, events -> { }, event -> { });
    }

    @Test
    void 语义重复时合并而不是新建() {
        Experience existing = experience("e1", "改代码时", 0.5);
        when(experiences.findActive()).thenReturn(Collections.singletonList(existing));

        service(RuntimePolicy.defaults()).apply(
                new ExperienceDraft("改代码时", "先读再改", "不返工"), SessionId.of("s1"));

        assertTrue(existing.confidence().getValue() > 0.5, "合并应提置信度");
    }

    @Test
    void 容量满时先淘汰最早再新建() {
        RuntimePolicy tiny = RuntimePolicy.defaults()
                .adjust(Collections.<String, Object>singletonMap("experienceCapacity", 1));
        Experience old = experience("e1", "旧场景", 0.5);
        when(experiences.findActive()).thenReturn(Collections.singletonList(old));
        when(experiences.count()).thenReturn(1);

        service(tiny).apply(new ExperienceDraft("新场景", "新教训", null), SessionId.of("s1"));

        assertEquals(ExperienceStatus.ARCHIVED, old.status(), "容量满时淘汰最早的经验");
    }

    @Test
    void 无重复且容量够则新建() {
        when(experiences.findActive()).thenReturn(Collections.<Experience>emptyList());
        when(experiences.count()).thenReturn(0);

        service(RuntimePolicy.defaults()).apply(new ExperienceDraft("新场景", "新教训", null), SessionId.of("s1"));

        org.mockito.Mockito.verify(commitScope).stageReplace(any(Experience.class));
    }

    private static Experience experience(String id, String scenario, double confidence) {
        return new Experience(ExperienceId.of(id), scenario, new LessonLearned("旧", "旧"),
                new ConfidenceScore(confidence), ExperienceStatus.ACTIVE, 0, 1L);
    }
}
