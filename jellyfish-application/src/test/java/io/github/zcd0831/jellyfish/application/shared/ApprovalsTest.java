package io.github.zcd0831.jellyfish.application.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工审批判定：默认不通过，只有明确肯定才放行。
 * 规则出处：06-planning §6.8.2 规则 5（人工层返回同意 / 不同意 + 必填原因）。
 */
class ApprovalsTest {

    @Test
    void 空回答不放行() {
        // 一个空回车就能放行审批，是所有绕过里成本最低的一条
        assertFalse(Approvals.isApproved(""));
        assertFalse(Approvals.isApproved("   "));
        assertFalse(Approvals.isApproved("\n\t"));
        assertFalse(Approvals.isApproved(null));
    }

    @Test
    void 带不字的否定都不放行() {
        // 否定表漏词时，"不继续" 会命中 "继续" 而被当成放行
        assertFalse(Approvals.isApproved("不继续"));
        assertFalse(Approvals.isApproved("不同意"));
        assertFalse(Approvals.isApproved("不可以"));
        assertFalse(Approvals.isApproved("不用了"));
        assertFalse(Approvals.isApproved("不要执行"));
        assertFalse(Approvals.isApproved("暂不"));
    }

    @Test
    void 其它否定形式也不放行() {
        assertFalse(Approvals.isApproved("拒绝"));
        assertFalse(Approvals.isApproved("驳回"));
        assertFalse(Approvals.isApproved("取消"));
        assertFalse(Approvals.isApproved("算了"));
        assertFalse(Approvals.isApproved("否"));
    }

    @Test
    void 明确肯定才放行() {
        assertTrue(Approvals.isApproved("同意"));
        assertTrue(Approvals.isApproved("可以"));
        assertTrue(Approvals.isApproved("继续"));
        assertTrue(Approvals.isApproved("确认"));
        assertTrue(Approvals.isApproved("批准"));
        assertTrue(Approvals.isApproved("好的，继续"));
        assertTrue(Approvals.isApproved("y"));
        assertTrue(Approvals.isApproved("YES"));
        assertTrue(Approvals.isApproved(" 同意 "));
    }

    @Test
    void 明确否定与含糊回答都不放行() {
        assertFalse(Approvals.isApproved("n"));
        assertFalse(Approvals.isApproved("No"));
        assertFalse(Approvals.isApproved("再看看"));
        assertFalse(Approvals.isApproved("随便"));
    }
}
