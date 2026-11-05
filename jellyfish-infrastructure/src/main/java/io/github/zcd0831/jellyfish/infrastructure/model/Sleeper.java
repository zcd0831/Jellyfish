package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;

/** 等待一段退避时间；终止信号应当在 {@code millis} 之内把它唤醒（08-application §8.9.4.1）。 */
interface Sleeper {

    void sleep(long millis, CancellationToken token) throws InterruptedException;
}
