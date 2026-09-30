package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LifecycleVerdict} 的单元测试：锁住「两态、放行是单例」这条契约。
 *
 * @author zcd
 */
@DisplayName("LifecycleVerdict 生命周期裁定")
class LifecycleVerdictTest {

    @Test
    void proceed_should_return_singleton() {
        // When
        LifecycleVerdict verdict = LifecycleVerdict.proceed();

        // Then
        assertFalse(verdict.isCancelled());
        assertNull(verdict.getReason());
        assertSame(LifecycleVerdict.proceed(), verdict);
    }

    @Test
    void cancel_should_carry_reason() {
        // When
        LifecycleVerdict verdict = LifecycleVerdict.cancel("还有未保存的改动");

        // Then
        assertTrue(verdict.isCancelled());
        assertEquals("还有未保存的改动", verdict.getReason());
    }

    @Test
    void cancel_should_tolerate_missing_reason() {
        // 插件常常懒得写理由：内核侧写固定占位，而不是因此拒掉整条裁定
        LifecycleVerdict verdict = LifecycleVerdict.cancel(null);

        assertTrue(verdict.isCancelled());
        assertNull(verdict.getReason());
    }
}
