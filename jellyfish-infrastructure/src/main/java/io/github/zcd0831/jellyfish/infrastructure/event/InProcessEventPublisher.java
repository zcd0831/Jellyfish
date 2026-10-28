package io.github.zcd0831.jellyfish.infrastructure.event;

import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;

import java.util.List;

/**
 * 提交后同步派发；本期没有订阅方，只做通知（不驱动 IO）。
 * 规则出处：08-application §8.8.3 纪律 2、09-infrastructure §9.4。
 */
public final class InProcessEventPublisher implements DomainEventPublisher {

    @Override
    public void publishAfterCommit(List<?> events) {
        // S1 无订阅方：接口存在即契约成立，订阅方随切片加入
    }
}
