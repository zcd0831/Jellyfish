package io.github.zcd0831.jellyfish.application.shared;

/**
 * 人工审批的判定：**先看否定再看肯定**——"不同意"里含着"同意"，
 * 直接 contains("同意") 会把拒绝当成放行。
 * 规则出处：模块-规划 §7.2 规则 5（人工层返回同意/不同意 + 原因）。
 */
public final class Approvals {

    private Approvals() {
    }

    public static boolean isApproved(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes")) {
            return true;
        }
        if (trimmed.contains("不同意") || trimmed.contains("拒绝") || trimmed.contains("不行")
                || trimmed.equalsIgnoreCase("n") || trimmed.equalsIgnoreCase("no")) {
            return false;
        }
        return trimmed.contains("同意") || trimmed.contains("可以") || trimmed.contains("继续");
    }
}
