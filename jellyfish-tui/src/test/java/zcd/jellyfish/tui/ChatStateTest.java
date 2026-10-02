package zcd.jellyfish.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.core.input.InputDirectiveRun;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.api.extension.ToolRenderHint;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    /** 空提示表：绝大多数用例不关心插件对工具行的表态。 */
    private static final Map<String, ToolRenderHint> EMPTY_HINTS =
            Collections.emptyMap();

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
    @DisplayName("没有绑定指令时中断是安全的空操作")
    void cancelDirective_should_be_noop_when_none() {
        assertDoesNotThrow(new Executable() {
            @Override
            public void execute() {
                state.cancelDirective();
            }
        });
    }

    @Test
    @DisplayName("回合正常结束后不再判定为进行中")
    void isTurnRunning_should_be_false_after_finish() {
        state.beginWork();
        assertTrue(state.isTurnRunning());

        state.getInflight().finish(InflightTurn.Outcome.COMPLETED, null);

        assertFalse(state.isTurnRunning());
    }

    @Test
    @DisplayName("输入指令与回合并用同一个暂存区：beginWork 后即处于进行中")
    void beginWork_should_markRunning() {
        state.beginWork();

        assertTrue(state.isTurnRunning());
        assertNull(state.getDirective());
    }

    @Test
    @DisplayName("绑定指令后 getDirective 返回它，clearDirective 清掉")
    void bindDirective_should_exposeHandle() {
        InputDirectiveRun run = new InputDirectiveRun("r1", "!", "!ls");

        state.bindDirective(run);
        assertEquals(run, state.getDirective());

        state.clearDirective();
        assertNull(state.getDirective());
    }

    @Test
    @DisplayName("Esc 中断输入指令")
    void cancelDirective_should_cancel_bound_directive() {
        InputDirectiveRun run = new InputDirectiveRun("r1", "!", "!ls");
        state.beginWork();
        state.bindDirective(run);

        state.cancelDirective();

        assertTrue(run.isCancelled());
    }

    @Test
    @DisplayName("beginWork 应清掉上一条指令句柄，避免过期句柄被取消")
    void beginWork_should_clear_directive() {
        state.bindDirective(new InputDirectiveRun("r1", "!", "!ls"));

        state.beginWork();

        assertNull(state.getDirective());
    }

    @Test
    @DisplayName("首页投影：字标与提示按视口高度垂直居中")
    void view_should_centerHomeSplashVertically() {
        ChatState.View view = state.view(null, messages(), 80, 21, MAX_MESSAGES, EMPTY_HINTS);

        List<String> body = texts(view);
        int contentRows = 5 + HomeHints.LEADING_BLANK_ROWS + HomeHints.HINTS.length;
        int blankAbove = 0;
        while (blankAbove < body.size() && body.get(blankAbove).isEmpty()) {
            blankAbove++;
        }

        // 8 行内容（5 行图案 + 空行 + 2 行提示），21 - 8 = 13，上 6 下 7
        assertEquals(6, blankAbove, "上半留白，实际：" + body);
        assertEquals(6 + contentRows, body.size(), "留白 + 内容，实际：" + body);
        assertEquals(0, view.getHiddenBelowRows(), "居中时内容不超过一屏，不该有下方隐藏");
        assertEquals(0, state.getOffset(), "居中时不该出现「上方有内容」的偏移");
    }

    @Test
    @DisplayName("终端变高后首页留白重算：视口行数也是投影的输入")
    void view_should_reprojectHomeWhenViewportGrows() {
        int contentRows = 5 + HomeHints.LEADING_BLANK_ROWS + HomeHints.HINTS.length;
        state.view(null, messages(), 80, 11, MAX_MESSAGES, EMPTY_HINTS);
        assertEquals(1 + contentRows, state.getTotalRows(), "11 - 8 = 3，顶部留 1 行 + 8 行内容");

        state.view(null, messages(), 80, 21, MAX_MESSAGES, EMPTY_HINTS);

        assertEquals(6 + contentRows, state.getTotalRows(), "只比列数缓存的话，这里会留在旧高度的行数上");
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
    @DisplayName("beginWork 重置暂存区，使新回合的正文立即可见")
    void beginWork_should_make_new_turn_content_visible() {
        state.beginWork();
        state.getInflight().appendText("第一回合");
        view(messages(), 5);
        state.getInflight().finish(InflightTurn.Outcome.COMPLETED, null);
        view(messages(), 5);

        state.beginWork();
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

    @Test
    @DisplayName("插件通知按来源封顶：一个插件刷屏不该把别的插件的通知挤掉")
    void view_should_boundPluginNoticesPerOwner() {
        // Given：两个来源，各自都往届一刷
        for (int i = 0; i < ChatState.MAX_NOTICES_PER_PLUGIN + 5; i++) {
            state.appendPluginNotice("plugin-a", "a" + i, ShellNotice.Kind.INFO);
        }
        state.appendPluginNotice("plugin-b", "b0", ShellNotice.Kind.WARN);

        // When
        String body = String.join("\n", texts(view(messages(), 400)));

        // Then：A 只留最新三条，且 B 的一条仍在——全局上限封顶的是总量，不是「谁先来谁占满」
        assertEquals(ChatState.MAX_NOTICES_PER_PLUGIN + 1, countNonBlank(texts(view(messages(), 400))));
        assertFalse(body.contains("a0"), body);
        assertTrue(body.contains("a" + (ChatState.MAX_NOTICES_PER_PLUGIN + 4)), body);
        assertTrue(body.contains("b0"), body);
    }

    @Test
    @DisplayName("插件通知的空白文本被忽略：空内容不是「清空此前的通知」")
    void appendPluginNotice_should_ignoreBlankText() {
        state.appendPluginNotice("plugin-a", "hello", ShellNotice.Kind.INFO);
        state.appendPluginNotice("plugin-a", "   ", ShellNotice.Kind.INFO);
        state.appendPluginNotice("plugin-a", null, ShellNotice.Kind.INFO);

        assertEquals(1, countNonBlank(texts(view(messages(), 40))));
    }

    /**
     * 数一数非空行。
     *
     * @param lines 行列表
     * @return 非空行数
     */
    private static int countNonBlank(List<String> lines) {
        int count = 0;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("默认折叠思考过程：历史里只留一行带字数的提示")
    void view_should_fold_thinking_by_default() {
        List<String> body = texts(view(thinkingMessages(), 20));

        assertTrue(body.contains("      \u273b 思考过程（4 字，Ctrl+T 展开）"), "实际：" + body);
        assertFalse(body.contains("      \u273b 先想一下"));
    }

    @Test
    @DisplayName("切换思考开关必须重投影：否则缓存会把开关吃掉")
    void view_should_reproject_when_thinkingToggled() {
        // 先投影一帧把缓存填上，消息列表与尺寸都不再变化
        view(thinkingMessages(), 20);

        assertTrue(state.toggleThinking(), "首次切换后应为已展开");
        List<String> body = texts(view(thinkingMessages(), 20));

        assertTrue(body.contains("      \u273b 先想一下"), "展开后应铺出全文，实际：" + body);
        assertFalse(body.contains("Ctrl+T 展开"));
    }

    @Test
    @DisplayName("预置展开态：构造 --show-thinking 启动时的初始状态")
    void setThinkingExpanded_should_seed_initial_state() {
        state.setThinkingExpanded(true);

        assertTrue(state.isThinkingExpanded());
        assertTrue(texts(view(thinkingMessages(), 20)).contains("      \u273b 先想一下"));
    }

    @Test
    @DisplayName("工具参数默认封顶，展开开关必须重投影：否则缓存会把开关吃掉")
    void view_should_reproject_when_toolArgumentsToggled() {
        List<SessionMessage> messages = toolCallMessages();
        List<String> collapsed = texts(view(messages, 20));
        assertTrue(collapsed.contains(TranscriptProjector.TRACE_INDENT
                + "\u2026 参数过长，已省略后续内容（Ctrl+E 展开）"), "实际：" + collapsed);

        assertTrue(state.toggleToolArguments(), "首次切换后应为已展开");
        List<String> expanded = texts(view(messages, 20));

        assertFalse(expanded.contains("已省略后续内容"), "展开后应铺开，实际：" + expanded);
        assertTrue(expanded.size() > collapsed.size(), "展开后行数应变多");

        assertFalse(state.toggleToolArguments(), "再切换应回到折叠");
        assertFalse(state.isToolArgumentsExpanded());
    }

    /**
     * 构造一轮带长工具参数的会话：折叠态下参数必定超过行数上限。
     *
     * @return 消息列表
     */
    private static List<SessionMessage> toolCallMessages() {
        StringBuilder command = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            command.append('x');
        }
        LlmToolCall call = new LlmToolCall(0, "call_1", "shell",
                "{\"command\":\"" + command + "\"}");
        return Arrays.asList(
                SessionMessage.of(LlmMessage.assistant("", Collections.singletonList(call))),
                SessionMessage.of(LlmMessage.tool("call_1", "shell", "输出")));
    }

    /**
     * 构造一条带思考过程的助手消息。
     *
     * @return 消息列表
     */
    private static List<SessionMessage> thinkingMessages() {
        return Collections.singletonList(
                SessionMessage.of(LlmMessage.assistant("答案"), null, "先想一下"));
    }

    /**
     * 以默认宽度与上限执行一次 view。
     *
     * @param messages     消息列表
     * @param viewportRows 可见行数
     * @return 窗口
     */
    private ChatState.View view(List<SessionMessage> messages, int viewportRows) {
        return state.view("s1", messages, WIDTH, viewportRows, MAX_MESSAGES, EMPTY_HINTS);
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
        return state.view(sessionId, messages, WIDTH, viewportRows, MAX_MESSAGES, EMPTY_HINTS);
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
