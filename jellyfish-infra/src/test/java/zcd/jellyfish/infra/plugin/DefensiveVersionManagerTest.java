package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefensiveVersionManager} 的单元测试：验证「非法表达式降级为不满足」而不是抛异常。
 *
 * @author zcd
 */
class DefensiveVersionManagerTest {

    /** 被测版本管理器。 */
    private final DefensiveVersionManager versionManager = new DefensiveVersionManager();

    @Test
    void checkVersionConstraint_should_delegate_when_constraint_satisfied() {
        // When / Then
        assertTrue(versionManager.checkVersionConstraint("1.0.0", ">=0.0.1"));
    }

    @Test
    void checkVersionConstraint_should_delegate_when_constraint_unsatisfied() {
        // When / Then
        assertFalse(versionManager.checkVersionConstraint("0.0.1", "^1.0.0"));
    }

    @Test
    void checkVersionConstraint_should_return_false_when_constraint_malformed() {
        // When / Then：semver 会因预发布后缀抛异常，这里必须降级而不是外溢
        assertFalse(versionManager.checkVersionConstraint("0.0.1", "0.0.1-SNAPSHOT"));
    }

    @Test
    void isValidConstraint_should_return_true_when_blank_or_wildcard() {
        // Then
        assertTrue(versionManager.isValidConstraint(null));
        assertTrue(versionManager.isValidConstraint("  "));
        assertTrue(versionManager.isValidConstraint("*"));
    }

    @Test
    void isValidConstraint_should_return_true_when_expression_well_formed() {
        // Then
        assertTrue(versionManager.isValidConstraint("0.0.1"));
        assertTrue(versionManager.isValidConstraint(">=0.0.1"));
        assertTrue(versionManager.isValidConstraint("^0.0.1"));
    }

    @Test
    void isValidConstraint_should_return_false_when_expression_malformed() {
        // Then
        assertFalse(versionManager.isValidConstraint("0.0.1-SNAPSHOT"));
        assertFalse(versionManager.isValidConstraint("not-a-version"));
    }

    @Test
    void compareVersions_should_delegate() {
        // When / Then
        assertEquals(0, versionManager.compareVersions("1.0.0", "1.0.0"));
        assertTrue(versionManager.compareVersions("2.0.0", "1.0.0") > 0);
    }
}
