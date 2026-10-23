package io.github.zcd0831.jellyfish.application.shared.port;

/**
 * 时间来源，便于测试替换。规则出处：模块-应用层 §6。
 */
public interface Clock {

    long nowMillis();
}
