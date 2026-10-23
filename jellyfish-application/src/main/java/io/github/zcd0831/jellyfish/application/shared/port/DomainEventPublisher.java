package io.github.zcd0831.jellyfish.application.shared.port;

import java.util.List;

/**
 * 提交后同步派发，只做通知，不得驱动 IO 长任务。规则出处：模块-应用层 §8.8、技术决策 D15。
 */
public interface DomainEventPublisher {

    void publishAfterCommit(List<?> events);
}
