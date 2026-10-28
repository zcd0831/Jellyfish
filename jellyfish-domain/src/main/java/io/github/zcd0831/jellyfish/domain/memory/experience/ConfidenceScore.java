package io.github.zcd0831.jellyfish.domain.memory.experience;

import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import lombok.Value;

/** 置信度恒在 [0,1]。规则出处：07-memory §7.4.1 规则 5。 */
@Value
public class ConfidenceScore {

    double value;

    public ConfidenceScore(double value) {
        if (value < 0 || value > 1) {
            throw new DomainInvariantViolation("置信度必须落在 [0,1]：" + value);
        }
        this.value = value;
    }
}
