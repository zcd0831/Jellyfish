package zcd.jellyfish.api.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskAnswer} 的单元测试：钉住「五种处境不能混成一种」这条口径。
 * <p>
 * 提问方（工具、插件）拿到的是一个终态，它必须能区分「用户选了」「用户自己写了」「用户放弃了」
 * 「没人回答」「这里根本没有人可问」——这几种处境下模型该做的下一件事完全不同。
 *
 * @author zcd
 */
@DisplayName("AskAnswer 提问答复")
class AskAnswerTest {

    @Test
    @DisplayName("选中候选项时只带选项标识")
    void answered_should_carry_option_id_only() {
        // When
        AskAnswer answer = AskAnswer.answered("2");

        // Then
        assertEquals(AskAnswer.Status.ANSWERED, answer.getStatus());
        assertEquals("2", answer.getOptionId());
        assertNull(answer.getText());
        assertNull(answer.getReason());
        assertTrue(answer.isAnswered());
    }

    @Test
    @DisplayName("用户自己输入时只带原文")
    void custom_should_carry_text_only() {
        // When
        AskAnswer answer = AskAnswer.custom("都不合适");

        // Then
        assertEquals(AskAnswer.Status.ANSWERED, answer.getStatus());
        assertNull(answer.getOptionId());
        assertEquals("都不合适", answer.getText());
        assertTrue(answer.isAnswered());
    }

    @Test
    @DisplayName("放弃作答时不是「拿到了答案」")
    void cancelled_should_not_count_as_answered() {
        // When
        AskAnswer answer = AskAnswer.cancelled("用户取消了这次提问，没有回答");

        // Then
        assertEquals(AskAnswer.Status.CANCELLED, answer.getStatus());
        assertFalse(answer.isAnswered());
        assertEquals("用户取消了这次提问，没有回答", answer.getReason());
        assertNull(answer.getOptionId());
        assertNull(answer.getText());
    }

    @Test
    @DisplayName("超时与「无人可问」是两种状态，不合并")
    void timed_out_and_unavailable_should_stay_distinct() {
        // When
        AskAnswer timedOut = AskAnswer.timedOut("用户在 120 秒内没有回答");
        AskAnswer unavailable = AskAnswer.unavailable("当前外壳没有交互界面，无法把提问送达用户");

        // Then：前者换个时机问可能就有答案，后者重试没有用；提问方据此决定要不要换问法
        assertEquals(AskAnswer.Status.TIMED_OUT, timedOut.getStatus());
        assertEquals(AskAnswer.Status.UNAVAILABLE, unavailable.getStatus());
        assertFalse(timedOut.isAnswered());
        assertFalse(unavailable.isAnswered());
    }

    @Test
    @DisplayName("全参构造器接受的字段原样保留")
    void constructor_should_keep_given_values() {
        // When
        AskAnswer answer = new AskAnswer(AskAnswer.Status.ANSWERED, "a", "补充", "说明");

        // Then
        assertEquals(AskAnswer.Status.ANSWERED, answer.getStatus());
        assertEquals("a", answer.getOptionId());
        assertEquals("补充", answer.getText());
        assertEquals("说明", answer.getReason());
    }
}
