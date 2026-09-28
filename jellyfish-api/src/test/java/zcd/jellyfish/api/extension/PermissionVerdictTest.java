package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionVerdict} 的单元测试：锁住「三态、且没有放行」这条契约。
 *
 * @author zcd
 */
@DisplayName("PermissionVerdict 权限拦截裁定")
class PermissionVerdictTest {

    @Test
    void abstain_should_return_singleton_without_reason() {
        // When
        PermissionVerdict verdict = PermissionVerdict.abstain();

        // Then
        assertTrue(verdict.isAbstain());
        assertFalse(verdict.isAsk());
        assertFalse(verdict.isDenied());
        assertNull(verdict.getReason());
        assertSame(PermissionVerdict.abstain(), verdict);
    }

    @Test
    void ask_should_carry_reason() {
        // When
        PermissionVerdict verdict = PermissionVerdict.ask("命令含写操作");

        // Then
        assertEquals(PermissionVerdict.Outcome.ASK, verdict.getOutcome());
        assertTrue(verdict.isAsk());
        assertFalse(verdict.isAbstain());
        assertFalse(verdict.isDenied());
        assertEquals("命令含写操作", verdict.getReason());
    }

    @Test
    void deny_should_carry_reason() {
        // When
        PermissionVerdict verdict = PermissionVerdict.deny("禁止删除根目录");

        // Then
        assertEquals(PermissionVerdict.Outcome.DENY, verdict.getOutcome());
        assertTrue(verdict.isDenied());
        assertFalse(verdict.isAbstain());
        assertFalse(verdict.isAsk());
        assertEquals("禁止删除根目录", verdict.getReason());
    }

    @Test
    void outcome_should_not_contain_allow() {
        // Then：没有「放行」这一态是编译期约束的运行时体现，插件无法借它放宽核心策略
        for (PermissionVerdict.Outcome outcome : PermissionVerdict.Outcome.values()) {
            assertFalse("ALLOW".equals(outcome.name()));
        }
    }
}
