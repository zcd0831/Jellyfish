package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmToolCall;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TokenEstimator} 的单元测试：验证中文 / 英文 / 空文本与消息级估算。
 *
 * @author zcd
 */
class TokenEstimatorTest {

    @Test
    void estimate_should_return_zero_for_blank_text() {
        // When / Then
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void estimate_should_round_up_english_by_quarter() {
        // When / Then：5 个 ASCII 字符 = 1.25 token，向上取整为 2
        assertEquals(2, TokenEstimator.estimate("hello"));
    }

    @Test
    void estimate_should_count_cjk_as_one_token_each() {
        // When / Then：2 个汉字 = 2 token
        assertEquals(2, TokenEstimator.estimate("你好"));
    }

    @Test
    void estimate_should_mix_cjk_and_english() {
        // When / Then：2 汉字 + 4 字母 = 2 + 1 = 3 token
        assertEquals(3, TokenEstimator.estimate("你好abcd"));
    }

    @Test
    void estimateMessage_should_add_overhead_and_include_tool_call() {
        // Given：一条纯文本消息，2 字符 = 1 token，加固定开销 4
        LlmMessage text = LlmMessage.user("hi");

        // When / Then
        assertEquals(5, TokenEstimator.estimateMessage(text));
    }

    @Test
    void estimateMessage_should_include_tool_calls() {
        // Given：带一次工具调用的 assistant 消息
        LlmToolCall call = new LlmToolCall(0, "call_1", "read", "{}");
        LlmMessage assistant = LlmMessage.assistant(null, Collections.singletonList(call));

        // When：4（消息开销）+ 2（调用开销）+ 1（read）+ 1（{}）
        int estimated = TokenEstimator.estimateMessage(assistant);

        // Then
        assertEquals(8, estimated);
    }

    @Test
    void estimateMessage_should_return_zero_for_null() {
        // When / Then
        assertEquals(0, TokenEstimator.estimateMessage(null));
    }

    @Test
    void estimateMessages_should_sum_each_message() {
        // Given
        LlmMessage first = LlmMessage.user("hi");
        LlmMessage second = LlmMessage.assistant("ok");

        // When / Then：5 +（4 + 1）= 10
        assertEquals(10, TokenEstimator.estimateMessages(Arrays.asList(first, second)));
    }
}
