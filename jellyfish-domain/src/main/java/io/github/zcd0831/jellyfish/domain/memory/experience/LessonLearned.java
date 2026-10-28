package io.github.zcd0831.jellyfish.domain.memory.experience;

import lombok.Value;

/** 教训正文 + 期望结果。规则出处：07-memory §7.6。 */
@Value
public class LessonLearned {

    String instruction;
    String expectedOutcome;
}
