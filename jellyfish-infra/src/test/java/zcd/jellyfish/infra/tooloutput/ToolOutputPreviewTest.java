package zcd.jellyfish.infra.tooloutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolOutputPreview} 的单元测试：锁住「头 30% + 尾 70% + 显式省略标记 + 不切开码点」。
 * <p>
 * 这里的断言刻意不写死具体切口位置：切口依赖换行位置，写死会让测试随无关改动一起红，
 * 而真正要守住的是「预算不超、头尾都在、标记写明省略量、没有半个码点」这几条性质。
 *
 * @author zcd
 */
@DisplayName("ToolOutputPreview 头尾预览")
class ToolOutputPreviewTest {

    @Test
    @DisplayName("预算切分为头 30% / 尾 70%")
    void budgets_should_beHeadThirtyTailSeventy() {
        assertEquals(30, ToolOutputPreview.headBudget(100));
        assertEquals(70, ToolOutputPreview.tailBudget(100));
        assertEquals(1, ToolOutputPreview.headBudget(5));
        assertEquals(4, ToolOutputPreview.tailBudget(5));
    }

    @Test
    @DisplayName("短文本不截断、不加标记")
    void text_should_passThroughShortText() {
        assertEquals("hello", ToolOutputPreview.text("hello", 100));
        assertEquals("", ToolOutputPreview.text(null, 100));
        assertEquals("", ToolOutputPreview.text("hello", 0));
    }

    @Test
    @DisplayName("超长文本应保留头尾并写明省略量")
    void text_should_keepHeadAndTail_withMarker() {
        // Given
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            source.append("line-").append(i).append('\n');
        }
        String text = source.toString();

        // When
        String preview = ToolOutputPreview.text(text, 400);

        // Then
        assertTrue(preview.length() <= 400, "预览长度 " + preview.length());
        assertTrue(preview.contains("省略"), preview);
        assertTrue(preview.contains("行"), preview);
        assertTrue(preview.contains("字符"), preview);
        assertTrue(preview.startsWith("line-0\n"), preview);
        assertTrue(preview.endsWith("line-499\n"), preview);
        assertFalse(preview.contains("line-250"), preview);
    }

    @Test
    @DisplayName("省略计数应与实际省略的行数一致")
    void text_should_reportExactOmittedCounts() {
        // Given：100 行、每行 10 字符，取出头尾后中段的行数可以精确算出
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            source.append("012345678\n");
        }
        String text = source.toString();
        int budget = 200;

        // When
        String preview = ToolOutputPreview.text(text, budget);
        String head = preview.substring(0, preview.indexOf("\n…"));
        String tail = preview.substring(preview.lastIndexOf("…\n") + 2);

        // Then：标记里的行数等于「总行数 - 保留的行数」
        long excluded = newlines(head) + newlines(tail);
        long reported = Long.parseLong(preview.replaceAll("(?s).*省略 (\\d+) 行.*", "$1"));
        assertEquals(100 - excluded, reported, preview);
    }

    @Test
    @DisplayName("预算过小时退化为只留头且不超过预算")
    void text_should_degradeToHeadOnly_whenBudgetTiny() {
        // Given：预算装不下省略标记
        String text = repeat("a", 500);

        // When
        String preview = ToolOutputPreview.text(text, 20);

        // Then
        assertEquals(20, preview.length());
        assertFalse(preview.contains("省略"));
    }

    @Test
    @DisplayName("切口不得切开代理对（emoji 不被劈成两半）")
    void text_should_notSplitSurrogatePair() {
        // Given：全是四字节字符，任何按字符数的切口都可能落在代理对中间
        String text = repeat("\uD83D\uDE00", 300);

        // When
        for (int budget = 64; budget <= 260; budget += 7) {
            String preview = ToolOutputPreview.text(text, budget);

            // Then：整段文本里不应出现落单的代理字符
            assertFalse(hasLoneSurrogate(preview), "budget=" + budget + " 出现了半个码点");
            assertTrue(preview.length() <= budget, "budget=" + budget);
        }
    }

    @Test
    @DisplayName("head 与 tail 应各自识别换行边界")
    void headAndTail_should_preferNewlineBoundaries() {
        // Given：正文里每 8 个字符一个换行
        String text = "abcdefg\nhijklmn\nopqrstu\nvwxyz01\n2345678\n9abcdef";

        // Then：head 在换行处收尾（保留换行）、tail 从换行之后开始
        assertEquals("abcdefg\n", ToolOutputPreview.head(text, 10));
        assertEquals("vwxyz01\n2345678\n9abcdef", ToolOutputPreview.tail(text, 24));
    }

    @Test
    @DisplayName("join 应在头尾之间插入标记并守住预算")
    void join_should_insertMarker_withinBudget() {
        // When
        String preview = ToolOutputPreview.join("head-", "-tail", 7L, 21L, 100);

        // Then
        assertEquals("head-\n… 省略 7 行 / 21 字符 …\n-tail", preview);
        assertTrue(preview.length() <= 100);
    }

    @Test
    @DisplayName("bodyBudget 应扣掉标记预留")
    void bodyBudget_should_reserveMarkerSpace() {
        assertEquals(152, ToolOutputPreview.bodyBudget(200));
        assertEquals(0, ToolOutputPreview.bodyBudget(10));
    }

    /**
     * 生成重复文本。
     *
     * @param text  文本片段
     * @param times 重复次数
     * @return 拼接结果
     */
    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString();
    }

    /**
     * 统计文本里的换行数。
     *
     * @param text 文本
     * @return 换行个数
     */
    private static long newlines(String text) {
        long lines = 0L;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * 判断文本里是否存在落单的代理字符。
     *
     * @param text 文本
     * @return 存在返回 {@code true}
     */
    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
