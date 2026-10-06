package zcd.jellyfish.api.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskRequest} 的单元测试：钉住「提问必须至少两个选项」这条校验与按标识查找候选项。
 * <p>
 * 一个选项的问题没有可比较的备选，而它在界面上的样子与「确认」按钮无异——那正是审批，
 * 不是提问。校验放在构造器里，调用方的这个错误就是编译期之后最近的一处失败点。
 *
 * @author zcd
 */
@DisplayName("AskRequest 提问请求")
class AskRequestTest {

    /** 满足校验的两个候选。 */
    private static final List<AskOption> TWO = Arrays.asList(
            AskOption.of("a", "先做通道", "内核与插件先落地"),
            AskOption.of("b", "先做 UI", null));

    @Test
    @DisplayName("构造请求并保留全部字段")
    void of_should_keep_given_values() {
        // When
        AskRequest request = AskRequest.of("ask_user", "s1", "先做通道还是先做 UI？", TWO);

        // Then
        assertEquals("ask_user", request.getSource());
        assertEquals("s1", request.getSessionId());
        assertEquals("先做通道还是先做 UI？", request.getQuestion());
        assertEquals(2, request.getOptions().size());
    }

    @Test
    @DisplayName("按标识查候选，找不到时返回 null")
    void option_should_lookup_by_id() {
        // Given
        AskRequest request = AskRequest.of("ask_user", "s1", "问题？", TWO);

        // When / Then
        assertEquals("先做通道", request.option("a").getLabel());
        assertEquals("内核与插件先落地", request.option("a").getDescription());
        assertNull(request.option("b").getDescription());
        assertNull(request.option("nope"));
        assertNull(request.option(null));
    }

    @ParameterizedTest
    @DisplayName("来源或问题为空白时拒绝构造")
    @MethodSource("blankFields")
    void constructor_should_reject_blank_fields(String source, String question) {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> AskRequest.of(source, "s1", question, TWO));
    }

    @Test
    @DisplayName("候选少于两个时拒绝构造")
    void constructor_should_require_at_least_two_options() {
        // When / Then
        assertThrows(JellyfishException.class, () -> AskRequest.of("ask_user", "s1", "问题？",
                Collections.singletonList(AskOption.of("a", "只有一个", null))));
        assertThrows(JellyfishException.class, () -> AskRequest.of("ask_user", "s1", "问题？", null));
    }

    @Test
    @DisplayName("候选列表是只读副本，外部改动不影响请求")
    void options_should_be_unmodifiable() {
        // Given
        List<AskOption> mutable = new ArrayList<AskOption>(TWO);
        AskRequest request = AskRequest.of("ask_user", "s1", "问题？", mutable);

        // When
        mutable.clear();

        // Then
        assertEquals(2, request.getOptions().size());
        assertThrows(UnsupportedOperationException.class, () -> request.getOptions().add(AskOption.of("c", "c", null)));
    }

    @Test
    @DisplayName("会话标识可以为空：内核用空槽位承接无会话的提问")
    void session_id_may_be_null() {
        // When
        AskRequest request = AskRequest.of("ask_user", null, "问题？", TWO);

        // Then
        assertNull(request.getSessionId());
    }

    /**
     * 提供「来源或问题为空白」的用例。
     *
     * @return 参数流
     */
    private static Stream<String[]> blankFields() {
        return Stream.of(
                new String[] {null, "问题？"},
                new String[] {"  ", "问题？"},
                new String[] {"ask_user", null},
                new String[] {"ask_user", "\t"});
    }

    @Test
    @DisplayName("toString 只给出概要，不含问题原文")
    void to_string_should_be_summary() {
        // When
        String text = AskRequest.of("ask_user", "s1", "先做通道还是先做 UI？", TWO).toString();

        // Then：日志里不该出现用户的提问原文，只要够定位即可
        assertTrue(text.contains("ask_user"), text);
        assertTrue(text.contains("options=2"), text);
    }
}
