package zcd.jellyfish.api.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubAgentPort} 与 {@link DelegationHandle} 的单元测试：能力缺失时的语义。
 * <p>
 * 重点不是「拒绝」，而是「拒绝<b>也</b>走 spawn → await，不抛异常」——插件因此不必为
 * 「内核没有这个能力」在正常路径上写分支。
 *
 * @author zcd
 */
@DisplayName("子代理委派端口")
class SubAgentPortTest {

    @Test
    @DisplayName("能力缺失端口返回同一个实例，spawn 从不抛异常")
    void unavailable_shouldReturnSingletonThatNeverThrows() {
        assertSame(SubAgentPort.unavailable(), SubAgentPort.unavailable());

        DelegationHandle handle = SubAgentPort.unavailable()
                .spawn(DelegationRequest.of("s-1", "scout", "查一下"));

        assertNull(handle.runId());
        assertEquals(DelegationStatus.REJECTED, handle.await().getStatus());
        assertTrue(handle.await().getError().contains("没有提供"));
        // 取消一个「还没开始就结束」的委派不该抛错
        handle.cancel();
    }

    @Test
    @DisplayName("能力缺失端口的额度恒为零：调用方在派生之前就停下")
    void unavailable_shouldReportZeroQuota() {
        DelegationQuota quota = SubAgentPort.unavailable().quota();

        // 派一个被拒一个不如根本不派：额度为零加上原因，调用方就能给出可执行的话
        assertTrue(quota.isBlocked());
        assertEquals(0, quota.getRemainingSpawns());
        assertTrue(quota.getBlockedReason().contains("没有提供"));
    }

    @Test
    @DisplayName("已终结的句柄重复等待返回同一个结果实例")
    void settled_shouldBeIdempotent() {        DelegationResult result = DelegationResult.completed("run-1", "结论", 3, 30L);
        DelegationHandle handle = DelegationHandle.settled(result);

        assertEquals("run-1", handle.runId());
        assertSame(result, handle.await());
        assertSame(handle.await(), handle.await());
    }

    @Test
    @DisplayName("被拒的结果没有 runId：编排方据此判断重试有没有意义")
    void rejected_shouldHaveNoRunId() {
        DelegationResult result = DelegationResult.rejected("开关关闭");
        assertNull(result.getRunId());
        assertEquals(DelegationStatus.REJECTED, result.getStatus());
        assertEquals("开关关闭", result.getError());
        assertFalse(result.hasText());
    }

    @Test
    @DisplayName("结果区分「没开始」与「开始了但失败」")
    void failed_shouldKeepRunId_unlikeRejected() {
        assertEquals("run-1", DelegationResult.failed("run-1", "模型不可用").getRunId());
        assertNull(DelegationResult.rejected("未知子代理类型").getRunId());
    }

    @Test
    @DisplayName("hasText 判空而非判 null")
    void hasText_shouldIgnoreBlankText() {
        assertFalse(DelegationResult.completed("run-1", "   ", 1, 1L).hasText());
        assertTrue(DelegationResult.completed("run-1", "结论", 1, 1L).hasText());
    }
}
