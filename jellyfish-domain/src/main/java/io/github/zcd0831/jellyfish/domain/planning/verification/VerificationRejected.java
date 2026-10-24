package io.github.zcd0831.jellyfish.domain.planning.verification;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 结论为 FAILED / UNDETERMINED。规则出处：模块-规划 §6。 */
public final class VerificationRejected implements DomainEvent {

    @Override
    public String name() {
        return "verification.rejected";
    }
}
