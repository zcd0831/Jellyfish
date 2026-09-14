package zcd.jellyfish.infra.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionPolicy} 的单元测试：验证「显式拒绝 / 需审批 / 允许收窄」三个集合的语义与不可变性。
 *
 * @author zcd
 */
class PermissionPolicyTest {

    @Test
    void unrestricted_should_be_empty_and_allow_everything() {
        // Given
        PermissionPolicy policy = PermissionPolicy.unrestricted();

        // Then
        assertTrue(policy.isEmpty());
        assertFalse(policy.denies("bash"));
        assertFalse(policy.requiresApproval("bash"));
        assertTrue(policy.allows("bash"));
    }

    @Test
    void of_should_report_empty_when_all_sets_absent() {
        // When / Then
        assertTrue(PermissionPolicy.of(null, null, null).isEmpty());
        assertTrue(PermissionPolicy.of(Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet()).isEmpty());
    }

    @Test
    void of_should_report_not_empty_when_only_allow_list_present() {
        // When
        PermissionPolicy policy = PermissionPolicy.of(null, null, setOf("read_file"));

        // Then
        assertFalse(policy.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"bash", "write_file"})
    void denies_should_match_denied_tools(String toolName) {
        // Given
        PermissionPolicy policy = PermissionPolicy.of(setOf("bash", "write_file"), null, null);

        // Then
        assertTrue(policy.denies(toolName));
    }

    @Test
    void denies_should_be_false_for_null_tool_name() {
        // Given
        PermissionPolicy policy = PermissionPolicy.of(setOf("bash"), null, null);

        // Then
        assertFalse(policy.denies(null));
    }

    @Test
    void requiresApproval_should_match_ask_tools() {
        // Given
        PermissionPolicy policy = PermissionPolicy.of(null, setOf("deploy"), null);

        // Then
        assertTrue(policy.requiresApproval("deploy"));
        assertFalse(policy.requiresApproval("read_file"));
        assertFalse(policy.requiresApproval(null));
    }

    @Test
    void allows_should_allow_any_tool_when_allow_list_empty() {
        // Given：允许集合为空表示「不限制」，即使其它集合非空也不影响
        PermissionPolicy policy = PermissionPolicy.of(setOf("bash"), null, null);

        // Then
        assertTrue(policy.allows("read_file"));
        assertTrue(policy.allows(null));
    }

    @Test
    void allows_should_reject_tool_outside_allow_list_when_not_empty() {
        // Given
        PermissionPolicy policy = PermissionPolicy.of(null, null, setOf("read_file", "list_dir"));

        // Then
        assertTrue(policy.allows("read_file"));
        assertFalse(policy.allows("bash"));
        assertFalse(policy.allows(null));
    }

    @Test
    void of_should_ignore_blank_and_null_entries() {
        // Given
        Set<String> tools = new LinkedHashSet<>(Arrays.asList("bash", "  ", null, ""));

        // When
        PermissionPolicy policy = PermissionPolicy.of(tools, null, null);

        // Then：只剩有效项，而「只剩一项也算有策略」，因此集合非空
        assertTrue(policy.denies("bash"));
        assertFalse(policy.isEmpty());
    }

    @Test
    void of_should_not_be_affected_by_later_input_mutation() {
        // Given
        Set<String> denied = new LinkedHashSet<>(Collections.singletonList("bash"));
        PermissionPolicy policy = PermissionPolicy.of(denied, null, null);

        // When
        denied.add("write_file");

        // Then
        assertFalse(policy.denies("write_file"));
    }

    /**
     * 构造可变工具名集合，避免测试里反复写 {@code new LinkedHashSet<>(...)}。
     *
     * @param values 工具名
     * @return 可变集合
     */
    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
