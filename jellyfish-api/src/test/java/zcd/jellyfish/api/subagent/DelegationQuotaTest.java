package zcd.jellyfish.api.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DelegationQuota} 的单元测试：额度快照的两个形态与非法输入。
 * <p>
 * 它的用处是让编排方<b>在派生任何子代理之前</b>判断「该不该开始」，因此本测试盯的是
 * 「还能派几个」与「派不了的原因」这两件事分得清不清。
 *
 * @author zcd
 */
@DisplayName("派生额度")
class DelegationQuotaTest {

    @Test
    @DisplayName("还能派时只带剩余名额，没有原因")
    void of_shouldCarryRemainingWithoutReason() {
        DelegationQuota quota = DelegationQuota.of(5);

        assertEquals(5, quota.getRemainingSpawns());
        assertNull(quota.getBlockedReason());
        assertFalse(quota.isBlocked());
    }

    @Test
    @DisplayName("零名额也算派不了：否则调用方会派一个被拒一个")
    void of_shouldTreatZeroAsBlocked() {
        DelegationQuota quota = DelegationQuota.of(0);

        assertTrue(quota.isBlocked());
        assertEquals(0, quota.getRemainingSpawns());
    }

    @Test
    @DisplayName("blocked 带一句可执行的原因，供调用方原样转述")
    void blocked_shouldCarryReason() {
        DelegationQuota quota = DelegationQuota.blocked("本次回合已派出 12 个子代理，达到上限 12，请自己完成剩余工作");

        assertTrue(quota.isBlocked());
        assertEquals(0, quota.getRemainingSpawns());
        assertTrue(quota.getBlockedReason().contains("达到上限 12"));
    }

    @Test
    @DisplayName("负名额是构造错误，当场拒绝")
    void constructor_shouldRejectNegativeRemaining() {
        assertThrows(JellyfishException.class, () -> new DelegationQuota(-1, null));
    }
}
