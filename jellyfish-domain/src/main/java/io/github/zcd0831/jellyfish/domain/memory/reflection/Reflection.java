package io.github.zcd0831.jellyfish.domain.memory.reflection;

import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
/** 规则出处：模块-记忆 §7.2。 */
public class Reflection {

    private ReflectionId id;
    private PlanId planId;
    private StepId stepId;
    private int attemptNo;
    private String conclusion;
    private long createdAt;
}
