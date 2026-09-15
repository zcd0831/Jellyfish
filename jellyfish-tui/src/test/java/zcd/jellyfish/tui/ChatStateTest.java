package zcd.jellyfish.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatState} 的单元测试。
 * <p>
 * 关注点：滚动窗口切片、跟随底部的进出、以及「用户上翻后不被新内容拽回底部」这条验收要求。
 *
 * @author zcd
 */
class ChatStateTest {

    /** 测试用宽度。 */
    private static final int WIDTH = 40;

    /** 测试用消息上限。 */
    private static final int MAX_MESSAGES = TranscriptProjector.DEFAULT_MAX_MESSAGES;

    /** 被测对象。 */
    private ChatState state;

    @BeforeEach
    void setUp() {
        state = new ChatState();
    }

    @Test
    @DisplayName("内容不足一屏时窗口显示全部行，且没有下方隐藏")
    void view_should_show_everything_when_content_shorter_than_viewport() {
        ChatState.View view = view(messages("a"), 20);

        assertEquals(2, view.getLines().size());
        assertEquals(0, view.getHiddenBelowRows());
        assertFalse(view.isHiddenAbove());
        assertTrue(view.isFollowingTail());
        assertNull(view.hiddenBelowHint());
    }

    @Test
    @DisplayName("内容超过一屏时默认跟随底部，显示的是最后若干行")
    void view_should_align_to_tail_when_following() {
        ChatState.View view = view(messages("1", "2", "3", "4", "5", "6"), 5);

        assertEquals(5, view.getLines().size());
        assertEquals(0, view.getHiddenBelowRows());
        assertTrue(view.isHiddenAbove(), "上方应有未显示内容");
        assertEquals("  \u276f 6", texts(view).get(texts(view).size() - 1), "最后一行应是最后一条消息");
    }

    @Test
    @DisplayName("向上滚动后停止跟随，窗口锚在用户位置")
    void scrollUp_should_stop_following() {
        view(messages("1", "2", "3", "4", "5", "6"), 5);
        assertTrue(state.isFollowingTail());

        state.scrollUp(2);
        ChatState.View view = view(messages("1", "2", "3", "4", "5", "6"), 5);

        assertFalse(view.isFollowingTail());
        assertTrue(view.getHiddenBelowRows() > 0, "停下后下方应有未显示内容");
    }

    @Test
    @DisplayName("用户上翻后新增内容不会把视图拽回底部")
    void view_should_keep_position_when_content_grows_while_not_following() {
        view(messages("1", "2", "3", "4", "5", "6"), 5);
        state.scrollUp(3);
        view(messages("1", "2", "3", "4", "5", "6"), 5);
        int offsetBefore = state.getOffset();

        ChatState.View view = view(messages("1", "2", "3", "4", "5", "6", "7", "8"), 5);

        assertEquals(offsetBefore, state.getOffset(), "窗口偏移不应被新内容移动");
        assertFalse(view.isFollowingTail());
        // 新增 2 条消息 = 4 个视觉行，全部落在窗口下方
        assertEquals(7, view.getHiddenBelowRows());
    }

    @Test
    @DisplayName("流式生成时下方提示只说「正在生成」，不报会跳动的行数")
    void hiddenBelowHint_should_say_streaming_when_turn_running() {
        view(messages("1", "2", "3", "4", "5", "6"), 5);
        state.scrollUp(3);
        state.getInflight().begin();
        state.getInflight().appendText("正在写回答…");

        ChatState.View view = view(messages("1", "2", "3", "4", "5", "6"), 5);

        assertEquals("\u2193 正在生成…", view.hiddenBelowHint());
    }

    @Test
    @DisplayName("空闲时下方提示报出行数")
    void hiddenBelowHint_should_report_rows_when_idle() {
        view(messages("1", "2", "3", "4", "5", "6"), 5);
        state.scrollUp(2);

        ChatState.View view = view(messages("1", "2", "3", "4", "5", "6"), 5);

        assertEquals("\u2193 2 行", view.hiddenBelowHint());
    }

    @Test
    @DisplayName("翻回最底部后恢复跟随")
    void scrollDown_should_resume_following_when_reaching_bottom() {
        view(messages("1", "2", "3", "4", "5", "6"), 5);
        state.scrollUp(100);
        view(messages("1", "2", "3", "4", "5", "6"), 5);
        assertFalse(state.isFollowingTail());

        state.scrollDown(1000);
        ChatState.View view = view(messages("1", "2", "3", "4", "5", "6"), 5);

        assertTrue(view.isFollowingTail());
        assertEquals(0, view.getHiddenBelowRows());
    }

    @Test
    @DisplayName("toBottom 无条件恢复跟随")
    void toBottom_should_resume_following() {
        view(messages("1", "2", "3", "4", "5", "6"), 5);
        state.scrollUp(3);

        state.toBottom();

        assertTrue(state.isFollowingTail());
    }

    @Test
    @DisplayName("向上滚动不会越过内容顶端")
    void scrollUp_should_clamp_at_top() {
        view(messages("1", "2", "3"), 20);

        state.scrollUp(100);
        ChatState.View view = view(messages("1", "2", "3"), 20);

        assertEquals(0, state.getOffset());
        assertFalse(view.isHiddenAbove());
    }

    @Test
    @DisplayName("翻页按可见行数减一移动")
    void pageUp_should_move_by_visible_rows() {
        view(messages("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), 4);
        int before = state.getOffset();

        state.pageUp();
        view(messages("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), 4);

        assertEquals(before - 3, state.getOffset());
    }

    @Test
    @DisplayName("行数为 0 或负数时滚动请求被忽略")
    void scroll_should_ignore_non_positive_rows() {
        view(messages("1", "2"), 20);
        int before = state.getOffset();

        state.scrollUp(0);
        state.scrollUp(-5);
        state.scrollDown(0);
        state.scrollDown(-5);

        assertEquals(before, state.getOffset());
    }

    @Test
    @DisplayName("中断转交给当前回合句柄")
    void cancelTurn_should_delegate_to_bound_turn() {
        ReActTurn turn = mock(ReActTurn.class);
        state.bindTurn(turn);

        state.cancelTurn();

        verify(turn).cancel();
    }

    @Test
    @DisplayName("没有绑定回合时中断是安全的空操作")
    void cancelTurn_should_be_noop_when_no_turn() {
        assertDoesNotThrow(new Executable() {
            @Override
            public void execute() {
                state.cancelTurn();
            }
        });
    }

    @Test
    @DisplayName("回合正常结束后不再判定为进行中")
    void isTurnRunning_should_be_false_after_finish() {
        state.beginTurn(null);
        assertTrue(state.isTurnRunning());

        state.getInflight().finish(InflightTurn.Outcome.COMPLETED, null);

        assertFalse(state.isTurnRunning());
    }

    @Test
    @DisplayName("会话切换后立即反映新会话内容，证明视图确实是会话的投影")
    void view_should_reflect_new_session_when_session_changed() {
        view(messages("旧会话"), 20);

        ChatState.View view = view("s2", messages("新会话"), 20);

        assertTrue(texts(view).contains("  \u276f 新会话"));
        assertFalse(texts(view).contains("  \u276f 旧会话"));
    }

    @Test
    @DisplayName("会话未变时不重复投影，但内容变化后立刻反映")
    void view_should_republish_when_inflight_changes() {
        state.getInflight().begin();
        view(messages(), 5);

        state.getInflight().appendText("新内容");
        ChatState.View view = view(messages(), 5);

        assertTrue(texts(view).contains("    新内容"));
    }

    @Test
    @DisplayName("beginTurn 重置暂存区，使新回合的正文立即可见")
    void beginTurn_should_make_new_turn_content_visible() {
        state.beginTurn(null);
        state.getInflight().appendText("第一回合");
        view(messages(), 5);
        state.getInflight().finish(InflightTurn.Outcome.COMPLETED, null);
        view(messages(), 5);

        state.beginTurn(null);
        state.getInflight().appendText("第二回合");
        ChatState.View view = view(messages(), 5);

        assertTrue(texts(view).contains("    第二回合"), "新回合正文必须可见");
    }

    @Test
    @DisplayName("命令输出按时间戳插到消息之间，而不是永远贴在投影末尾")
    void view_should_interleave_notice_by_timestamp() {
        long base = System.currentTimeMillis();
        state.appendNotice("/help", "/help 输出", ShellNotice.Kind.INFO);
        List<SessionMessage> messages = Arrays.asList(
                message("m1", base - 1000L, "旧"),
                message("m2", base + 10_000L, "新"));

        List<String> body = texts(view(messages, 20));

        int older = body.indexOf("  \u276f 旧");
        int notice = body.indexOf("    \u23bf /help 输出");
        int newer = body.indexOf("  \u276f 新");
        assertTrue(older >= 0 && notice >= 0 && newer >= 0, "三行都应出现，实际：" + body);
        assertTrue(older < notice && notice < newer,
                "命令输出应落在它发生的那一刻，实际顺序：" + body);
        assertTrue(body.contains("  \u276f /help"), "命令原文应回显，实际：" + body);
        assertEquals("  \u276f 新", body.get(body.size() - 1), "最后一行应是提示之后的消息");
    }

    @Test
    @DisplayName("外壳提示有界：超出上限丢弃最旧的")
    void view_should_bound_notices() {
        for (int i = 0; i < ChatState.MAX_NOTICES + 10; i++) {
            state.appendNotice("n" + i, ShellNotice.Kind.INFO);
        }

        List<String> blocks = new ArrayList<String>();
        for (String line : texts(view(messages(), 400))) {
            if (!line.trim().isEmpty()) {
                blocks.add(line);
            }
        }

        assertEquals(ChatState.MAX_NOTICES, blocks.size(), "提示块数应被上限封顶");
        assertFalse(blocks.contains("    \u23bf n0"), "最旧的提示应已被丢弃");
        assertTrue(blocks.contains("    \u23bf n" + (ChatState.MAX_NOTICES + 9)), "最新的提示必须保留");
    }

    /**
     * 以默认宽度与上限执行一次 view。
     *
     * @param messages     消息列表
     * @param viewportRows 可见行数
     * @return 窗口
     */
    private ChatState.View view(List<SessionMessage> messages, int viewportRows) {
        return state.view("s1", messages, WIDTH, viewportRows, MAX_MESSAGES);
    }

    /**
     * 指定会话标识执行一次 view。
     *
     * @param sessionId    会话标识
     * @param messages     消息列表
     * @param viewportRows 可见行数
     * @return 窗口
     */
    private ChatState.View view(String sessionId, List<SessionMessage> messages, int viewportRows) {
        return state.view(sessionId, messages, WIDTH, viewportRows, MAX_MESSAGES);
    }

    /**
     * 构造用户消息列表。
     *
     * @param contents 各条内容
     * @return 消息列表
     */
    private static List<SessionMessage> messages(String... contents) {
        List<SessionMessage> list = new ArrayList<SessionMessage>();
        for (String content : contents) {
            list.add(SessionMessage.of(LlmMessage.user(content)));
        }
        return list;
    }

    /**
     * 构造一条带显式时间戳的用户消息。
     *
     * @param id      消息标识
     * @param timestamp 时间戳
     * @param content 正文
     * @return 会话消息
     */
    private static SessionMessage message(String id, long timestamp, String content) {
        return new SessionMessage(id, timestamp, LlmMessage.user(content), null);
    }

    /**
     * 构造空消息列表。
     *
     * @return 空列表
     */
    private static List<SessionMessage> messages() {
        return Collections.emptyList();
    }

    /**
     * 抽取窗口内每行的纯文本。
     *
     * @param view 窗口
     * @return 纯文本列表
     */
    private static List<String> texts(ChatState.View view) {
        List<String> result = new ArrayList<String>();
        for (VisualLine line : view.getLines()) {
            result.add(line.text());
        }
        return result;
    }

}
