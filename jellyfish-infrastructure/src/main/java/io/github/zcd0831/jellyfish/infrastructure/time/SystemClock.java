package io.github.zcd0831.jellyfish.infrastructure.time;

import io.github.zcd0831.jellyfish.application.shared.port.Clock;

/** 规则出处：09-infrastructure §9.4。 */
public final class SystemClock implements Clock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
