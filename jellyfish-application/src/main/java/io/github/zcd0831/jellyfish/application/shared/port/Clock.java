package io.github.zcd0831.jellyfish.application.shared.port;

/**
 * 时间来源，便于测试替换。规则出处：08-application §8.7。
 */
public interface Clock {

    long nowMillis();
}
