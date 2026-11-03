package io.github.zcd0831.jellyfish.shell.tui;

import java.util.List;

/**
 * 带选项时把一行输入解释成选择结果。纯函数，不碰终端与状态。
 * 规则出处：08-application §8.5（带 options 时按编号选，自动追加「其他（自己输入）」，
 * 无效输入不算回答）。
 */
final class ChoiceInput {

    /** 解析结果：三选一。 */
    static final class Result {

        /** 直接得到的答案；非 null 表示这行就是回答。 */
        final String answer;
        /** 选中了「其他（自己输入）」：下一行文本才是答案。 */
        final boolean wantsFreeText;
        /** 不算回答，需要重新提示；内容是提示语。 */
        final String reject;

        private Result(String answer, boolean wantsFreeText, String reject) {
            this.answer = answer;
            this.wantsFreeText = wantsFreeText;
            this.reject = reject;
        }

        static Result answer(String value) {
            return new Result(value, false, null);
        }

        static Result freeText() {
            return new Result(null, true, null);
        }

        static Result reject(String message) {
            return new Result(null, false, message);
        }
    }

    private ChoiceInput() {
    }

    /**
     * @param text       用户输入的一行（已 trim）
     * @param options    本条问题的选项；null/空表示自由文本回答
     * @param freeText   是否已处于「其他（自己输入）」阶段
     */
    static Result parse(String text, List<String> options, boolean freeText) {
        if (freeText || options == null || options.isEmpty()) {
            return text.isEmpty() ? Result.reject("空回答不算回答，请重新输入（Esc 终止）") : Result.answer(text);
        }
        if (options.contains(text)) {
            return Result.answer(text);   // 直接打选项原文也算选中
        }
        int escape = options.size() + 1;
        int choice;
        try {
            choice = Integer.parseInt(text);
        } catch (NumberFormatException notNumber) {
            return Result.reject("请按编号或选项原文回答（1-" + escape + "）");
        }
        if (choice < 1 || choice > escape) {
            return Result.reject("编号超出范围（1-" + escape + "）");
        }
        return choice == escape ? Result.freeText() : Result.answer(options.get(choice - 1));
    }
}
