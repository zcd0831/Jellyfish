package io.github.zcd0831.jellyfish.domain.memory.experience;

import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
/** 规则出处：模块-记忆 §7.1。 */
public class Experience {

    private ExperienceId id;
    private String applicableScenario;
    private LessonLearned lesson;
    private ConfidenceScore confidence;
    private ExperienceStatus status;
    private int appliedCount;
    private long createdAt;
}
