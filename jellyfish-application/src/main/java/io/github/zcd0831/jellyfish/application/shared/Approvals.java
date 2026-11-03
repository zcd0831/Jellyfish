package io.github.zcd0831.jellyfish.application.shared;

/**
 * 人工审批的判定：**先看否定再看肯定**——"不同意"里含着"同意"，
 * 直接 contains("同意") 会把拒绝当成放行。
 * <p>默认方向是<strong>不通过</strong>：只有明确肯定的回答才算同意，
 * 空回答、含糊回答、带否定词的回答一律不放行。
 * 规则出处：06-planning §6.8.2 规则 5（人工层返回同意 / 不同意 + 必填原因）。
 */
public final class Approvals {

    /** 否定标记：出现任意一个就不是同意（先于肯定词判定）。 */
    private static final String[] NEGATIVE_MARKERS = {
            "不", "否", "拒", "别", "勿", "停", "取消", "算了", "驳回"};

    /** 肯定标记：必须在没有否定标记的前提下才能匹配。 */
    private static final String[] AFFIRMATIVE_MARKERS = {
            "同意", "可以", "继续", "确认", "批准", "好的", "行", "是", "ok"};

    private Approvals() {
    }

    /**
     * 只有明确肯定才算同意。空串、空白串、含糊回答、含否定词的回答都返回 false —— fail-closed。
     */
    public static boolean isApproved(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            // 空回车不是"同意"：人工层要求返回同意/不同意，空回答不构成裁决
            return false;
        }
        if (trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("n") || trimmed.equalsIgnoreCase("no")) {
            return false;
        }
        if (containsAny(trimmed, NEGATIVE_MARKERS)) {
            return false;
        }
        return containsAny(trimmed, AFFIRMATIVE_MARKERS);
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
