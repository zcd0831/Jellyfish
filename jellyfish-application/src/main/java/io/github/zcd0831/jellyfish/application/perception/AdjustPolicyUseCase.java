package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.perception.policy.PolicyError;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;

import java.util.Map;

/**
 * 策略调整：校验失败即拒绝且不改，成功立即生效（改文件 + 重启同样生效）。
 * 规则出处：模块-应用层 §8.5、模块-感知 §7.2。
 */
public final class AdjustPolicyUseCase {

    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;

    public AdjustPolicyUseCase(PolicyRepository policies, CommitScope commitScope,
                               DomainEventPublisher eventPublisher) {
        this.policies = policies;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
    }

    public Result<RuntimePolicy> adjust(Map<String, Object> changes) {
        RuntimePolicy current = policies.load();
        RuntimePolicy adjusted;
        try {
            adjusted = current.adjust(changes);
        } catch (DomainInvariantViolation rejected) {
            return Result.fail(UseCaseError.of(PolicyError.INVALID_VALUE, rejected.getMessage(),
                    changes.keySet()));
        }
        commitScope.stageReplace(adjusted);
        commitScope.commit();
        eventPublisher.publishAfterCommit(java.util.Collections.<Object>singletonList(
                io.github.zcd0831.jellyfish.domain.shared.DomainEvent.class.cast(new PolicyAdjusted())));
        return Result.ok(adjusted);
    }

    /** 策略变更事件：其它模块据此刷新读取缓存。规则出处：模块-感知 §6。 */
    private static final class PolicyAdjusted implements io.github.zcd0831.jellyfish.domain.shared.DomainEvent {
        @Override
        public String name() {
            return "policy.adjusted";
        }
    }
}
