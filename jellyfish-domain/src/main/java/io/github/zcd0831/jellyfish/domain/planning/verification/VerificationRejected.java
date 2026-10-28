package io.github.zcd0831.jellyfish.domain.planning.verification;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 结论为 FAILED / UNDETERMINED。规则出处：06-planning §6.7。 */
public final class VerificationRejected implements DomainEvent {

    @Override
    public String name() {
        return "verification.rejected";
    }
}
