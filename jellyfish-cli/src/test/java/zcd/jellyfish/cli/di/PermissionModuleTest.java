package zcd.jellyfish.cli.di;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.permission.PermissionPolicyProvider;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionModule} 的单元测试：验证策略来源占位实现的行为，以及它不会意外收紧权限。
 *
 * @author zcd
 */
class PermissionModuleTest {

    @Test
    void providePermissionPolicyProvider_should_return_unrestricted_policy_for_any_agent() {
        // When
        PermissionPolicyProvider provider = PermissionModule.providePermissionPolicyProvider();

        // Then：AgentManager 未落地前一律「无策略」，判定按 fail-open 放行
        assertNotNull(provider);
        assertTrue(provider.policyOf("agent-a").isEmpty());
        assertTrue(provider.policyOf(null).isEmpty());
    }
}
