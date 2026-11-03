package io.github.zcd0831.jellyfish.shell.tui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带选项时的输入解释：编号、选项原文、「其他（自己输入）」、无效输入。
 * 规则出处：08-application §8.5（端口契约）。
 */
class ChoiceInputTest {

    private static final java.util.List<String> OPTIONS = Arrays.asList("同意", "不同意");

    @Test
    void 按编号选中对应选项() {
        assertEquals("同意", ChoiceInput.parse("1", OPTIONS, false).answer);
        assertEquals("不同意", ChoiceInput.parse("2", OPTIONS, false).answer);
    }

    @Test
    void 直接打选项原文也算选中() {
        assertEquals("同意", ChoiceInput.parse("同意", OPTIONS, false).answer);
    }

    @Test
    void 最后一项是其他并转自由输入() {
        ChoiceInput.Result escape = ChoiceInput.parse("3", OPTIONS, false);

        assertTrue(escape.wantsFreeText, "第 N+1 项固定是「其他（自己输入）」");
        assertNull(escape.answer);
    }

    @Test
    void 其他阶段的下一行文本就是答案() {
        ChoiceInput.Result free = ChoiceInput.parse("我自己写一个", OPTIONS, true);

        assertEquals("我自己写一个", free.answer);
    }

    @Test
    void 空行与越界编号不算回答() {
        ChoiceInput.Result blank = ChoiceInput.parse("", OPTIONS, false);
        ChoiceInput.Result outOfRange = ChoiceInput.parse("9", OPTIONS, false);
        ChoiceInput.Result notNumber = ChoiceInput.parse("随便", OPTIONS, false);

        assertNull(blank.answer);
        assertTrue(blank.reject.contains("1-3"), blank.reject);
        assertTrue(outOfRange.reject.contains("超出范围"), outOfRange.reject);
        assertTrue(notNumber.reject.contains("1-3"), notNumber.reject);
    }

    @Test
    void 没有选项时按自由文本且空行无效() {
        assertEquals("我自己说", ChoiceInput.parse("我自己说", null, false).answer);
        assertEquals("我自己说", ChoiceInput.parse("我自己说", Collections.<String>emptyList(), false).answer);
        assertNull(ChoiceInput.parse("", null, false).answer);
    }
}
