package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionDecision} 的单元测试：验证三态工厂与理由透出。
 *
 * @author zcd
 */
class PermissionDecisionTest {

    @Test
    void allow_should_produce_allowed_outcome() {
        // When
        PermissionDecision decision = PermissionDecision.allow("允许");

        // Then
        assertEquals(PermissionDecision.Outcome.ALLOW, decision.getOutcome());
        assertTrue(decision.isAllowed());
        assertFalse(decision.isDenied());
        assertFalse(decision.isAsk());
        assertEquals("允许", decision.getReason());
    }

    @Test
    void deny_should_produce_denied_outcome() {
        // When
        PermissionDecision decision = PermissionDecision.deny("不在允许范围内");

        // Then
        assertEquals(PermissionDecision.Outcome.DENY, decision.getOutcome());
        assertTrue(decision.isDenied());
        assertFalse(decision.isAllowed());
        assertFalse(decision.isAsk());
        assertEquals("不在允许范围内", decision.getReason());
    }

    @Test
    void ask_should_produce_ask_outcome() {
        // When
        PermissionDecision decision = PermissionDecision.ask("需要人工审批");

        // Then
        assertEquals(PermissionDecision.Outcome.ASK, decision.getOutcome());
        assertTrue(decision.isAsk());
        assertFalse(decision.isAllowed());
        assertFalse(decision.isDenied());
        assertEquals("需要人工审批", decision.getReason());
    }

    @Test
    void factories_should_allow_null_reason() {
        // When / Then
        assertNull(PermissionDecision.allow(null).getReason());
        assertNull(PermissionDecision.deny(null).getReason());
        assertNull(PermissionDecision.ask(null).getReason());
    }

    @Test
    void toString_should_contain_outcome_and_reason() {
        // When
        String text = PermissionDecision.deny("显式拒绝").toString();

        // Then
        assertTrue(text.contains("DENY"));
        assertTrue(text.contains("显式拒绝"));
    }
}
