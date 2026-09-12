package zcd.jellyfish.api.event.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionDecision} 的单元测试。
 *
 * @author zcd
 */
class PermissionDecisionTest {

    @Test
    void allow_should_grant_with_reason() {
        // Given
        PermissionDecision decision = PermissionDecision.allow("trusted");

        // Then
        assertTrue(decision.isGranted());
        assertEquals("trusted", decision.getReason());
    }

    @Test
    void deny_should_reject_with_reason() {
        // Given
        PermissionDecision decision = PermissionDecision.deny("not allowed");

        // Then
        assertFalse(decision.isGranted());
        assertEquals("not allowed", decision.getReason());
    }
}
