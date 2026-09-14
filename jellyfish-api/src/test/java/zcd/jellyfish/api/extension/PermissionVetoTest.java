package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionVeto} 的单元测试：验证「不拦截 / 拦截」两态与理由透出。
 * <p>
 * 「插件无法返回 ASK」由类型保证（本类没有 ask 工厂），因此这里只能覆盖这两个态。
 *
 * @author zcd
 */
class PermissionVetoTest {

    @Test
    void none_should_produce_not_denied_veto() {
        // When
        PermissionVeto veto = PermissionVeto.none();

        // Then
        assertFalse(veto.isDenied());
        assertNull(veto.getReason());
    }

    @Test
    void deny_should_produce_denied_veto_with_reason() {
        // When
        PermissionVeto veto = PermissionVeto.deny("计划模式下不允许写操作");

        // Then
        assertTrue(veto.isDenied());
        assertEquals("计划模式下不允许写操作", veto.getReason());
    }

    @Test
    void deny_should_allow_null_reason() {
        // When
        PermissionVeto veto = PermissionVeto.deny(null);

        // Then
        assertTrue(veto.isDenied());
        assertNull(veto.getReason());
    }

    @Test
    void toString_should_contain_denied_and_reason() {
        // When
        String text = PermissionVeto.deny("命中黑名单").toString();

        // Then
        assertTrue(text.contains("denied=true"));
        assertTrue(text.contains("命中黑名单"));
    }
}
