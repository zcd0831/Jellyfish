package io.github.zcd0831.jellyfish.application.perception.port;

import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;

/**
 * 单例策略。规则出处：09-infrastructure §9.4。
 */
public interface PolicyRepository {

    RuntimePolicy load();

    void save(RuntimePolicy policy);
}
