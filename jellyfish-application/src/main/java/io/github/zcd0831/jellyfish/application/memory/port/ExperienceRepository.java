package io.github.zcd0831.jellyfish.application.memory.port;

import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;

import java.util.List;
import java.util.Optional;

/**
 * 经验库。规则出处：09-infrastructure §9.4（`find` 供上下文注入使用）。
 */
public interface ExperienceRepository {

    List<Experience> findActive();

    Optional<Experience> find(ExperienceId id);

    void save(Experience experience);

    void archive(ExperienceId id);

    int count();
}
