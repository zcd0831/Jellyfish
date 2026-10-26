package io.github.zcd0831.jellyfish.shell.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 多行输入的两条路子：行尾 `\` 续行、整段（粘贴/Alt+Enter）直接提交。 */
class DraftInputTest {

    @Test
    void 普通一行直接完成() {
        DraftInput draft = new DraftInput();

        assertFalse(draft.accept("你好"));
        assertEquals("你好", draft.take());
    }

    @Test
    void 行尾反斜杠续行() {
        DraftInput draft = new DraftInput();

        assertTrue(draft.accept("第一行\\"));
        assertTrue(draft.isContinuing());
        assertFalse(draft.accept("第二行"));

        assertEquals("第一行\n第二行", draft.take());
        assertFalse(draft.isContinuing(), "取走后应清空");
    }

    @Test
    void 整段带换行的输入一次完成() {
        DraftInput draft = new DraftInput();

        assertFalse(draft.accept("第一行\n第二行"));

        assertEquals("第一行\n第二行", draft.take());
    }

    @Test
    void 粘贴内容以反斜杠结尾也不当续行() {
        DraftInput draft = new DraftInput();

        assertFalse(draft.accept("第一行\n路径 C:\\"), "已带换行的整段粘贴直接提交");

        assertEquals("第一行\n路径 C:\\", draft.take());
    }

    @Test
    void 清空用于放弃() {
        DraftInput draft = new DraftInput();
        draft.accept("第一行\\");

        draft.clear();

        assertFalse(draft.isContinuing());
        assertEquals("", draft.take());
    }
}
