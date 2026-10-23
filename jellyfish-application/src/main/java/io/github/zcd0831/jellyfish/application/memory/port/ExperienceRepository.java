package io.github.zcd0831.jellyfish.application.memory.port;

import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import java.util.List;

/**
 * 经验库。规则出处：模块-基础设施层 §3。
 */
public interface ExperienceRepository {

    List<Experience> findActive();

    void save(Experience experience);

    void archive(ExperienceId id);

    int count();
}
