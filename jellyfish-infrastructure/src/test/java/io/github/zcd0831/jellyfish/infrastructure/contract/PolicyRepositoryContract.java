package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** `PolicyRepository` 的契约：单例、缺失即写默认值、覆盖保存。 */
public abstract class PolicyRepositoryContract {

    protected abstract PolicyRepository repository();

    @Test
    void 首次读取返回默认值而不是null() {
        RuntimePolicy policy = repository().load();

        assertNotNull(policy);
        assertEquals(RuntimePolicy.defaults().maxReActRounds(), policy.maxReActRounds());
    }

    @Test
    void 保存后读取到的是最新值() {
        repository().save(repository().load().adjust(
                Collections.<String, Object>singletonMap("maxReActRounds", 33)));

        assertEquals(33, repository().load().maxReActRounds());
    }
}
