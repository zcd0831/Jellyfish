package io.github.zcd0831.jellyfish.infrastructure.time;

import io.github.zcd0831.jellyfish.application.shared.port.Clock;

/** 规则出处：模块-基础设施层 §3。 */
public final class SystemClock implements Clock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
