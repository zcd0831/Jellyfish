package zcd.jellyfish.api.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegisterOptions} 的单元测试：验证默认不覆盖与显式覆盖语义。
 *
 * @author zcd
 */
class RegisterOptionsTest {

    @Test
    void default_should_not_allow_override() {
        // Then
        assertFalse(RegisterOptions.DEFAULT.isOverride());
    }

    @Test
    void override_should_allow_override_when_true() {
        // Then
        assertTrue(RegisterOptions.override(true).isOverride());
    }

    @Test
    void override_should_return_default_when_false() {
        // Then
        assertSame(RegisterOptions.DEFAULT, RegisterOptions.override(false));
    }
}
