package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TranscriptProjector} 的单元测试。
 * <p>
 * 这是本外壳最需要断言的部分：视觉层级、角色前缀、工具轨迹折叠、以及「进行中回合」的渲染。
 * 投影是纯函数，因此这些断言不需要任何终端环境。
 *
 * @author zcd
 */
class TranscriptProjectorTest {

    /** 测试用宽度：足够宽，保证不触发换行，断言聚焦在层级而非换行。 */
    private static final int WIDE = 80;

    @Test
    @DisplayName("空会话只投影出进行中提示，不产生任何历史行")
    void project_should_emit_pending_notice_when_no_history_and_no_content() {
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), running("", ""));

        assertEquals(Collections.singletonList("      \u23bf 处理中\u2026"), texts(lines));
    }

    @Test
    @DisplayName("用户消息带 ❯ 前缀，前面有空行")
    void project_should_prefix_user_message() {
        List<VisualLine> lines = project(
                Collections.singletonList(SessionMessage.of(LlmMessage.user("你好"))),
                completed());

        assertEquals(Arrays.asList("", "  \u276f 你好"), texts(lines));
    }

    @Test
    @DisplayName("助手消息带 ⏺ jellyfish 表头，正文缩进一级")
    void project_should_prefix_assistant_message() {
        List<VisualLine> lines = project(
                Collections.singletonList(SessionMessage.of(LlmMessage.assistant("好的"))),
                completed());

        assertEquals(Arrays.asList("", "  \u23fa jellyfish", "    好的"), texts(lines));
    }

    @Test
    @DisplayName("相邻的助手与工具消息共用一个表头，避免多轮工具调用刷出一片重复标题")
    void project_should_share_single_header_across_assistant_and_tool() {
        List<SessionMessage> messages = Arrays.asList(
                SessionMessage.of(LlmMessage.user("清 TODO")),
                SessionMessage.of(LlmMessage.assistant("我先看看", Collections.emptyList())),
                SessionMessage.of(LlmMessage.tool("c1", "read_file", "内容")),
                SessionMessage.of(LlmMessage.tool("c2", "edit_file", "完成")),
                SessionMessage.of(LlmMessage.assistant("已清掉 3 个 TODO")));

        List<VisualLine> lines = project(messages, completed());

        assertEquals(Arrays.asList(
                "",
                "  \u276f 清 TODO",
                "",
                "  \u23fa jellyfish",
                "    我先看看",
                "      \u23bf read_file",
                "      \u23bf edit_file",
                "    已清掉 3 个 TODO"), texts(lines));
    }

    @Test
    @DisplayName("只带工具调用、没有正文的助手消息不单独占行")
    void project_should_skip_blank_assistant_body() {
        List<SessionMessage> messages = Arrays.asList(
                SessionMessage.of(LlmMessage.assistant("", Collections.emptyList())),
                SessionMessage.of(LlmMessage.tool("c1", "read_file", "内容")));

        List<VisualLine> lines = project(messages, completed());

        assertEquals(Arrays.asList("", "  \u23fa jellyfish", "      \u23bf read_file"), texts(lines));
    }

    @Test
    @DisplayName("工具消息缺名字时用「工具」兜底，不显示 null")
    void project_should_fallback_when_tool_name_missing() {
        List<VisualLine> messages = project(
                Collections.singletonList(SessionMessage.of(LlmMessage.tool("c1", null, "内容"))),
                completed());

        assertEquals(Arrays.asList("", "  \u23fa jellyfish", "      \u23bf 工具"), texts(messages));
    }

    @Test
    @DisplayName("进行中的回合把思考与正文都画出来，思考在正文之前")
    void project_should_render_inflight_thinking_before_text() {
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(),
                running("答案是 42", "用户想算加法"));

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u273b 用户想算加法",
                "    答案是 42"), texts(lines));
    }

    @Test
    @DisplayName("中断的回合补一行「已中断」")
    void project_should_emit_cancelled_notice() {
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(),
                finished(InflightTurn.Outcome.CANCELLED, null));

        assertEquals(Collections.singletonList("      \u23bf 已中断"), texts(lines));
    }

    @Test
    @DisplayName("未收敛的回合补一行最大轮次提示")
    void project_should_emit_truncated_notice() {
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(),
                finished(InflightTurn.Outcome.TRUNCATED, null));

        assertEquals(Collections.singletonList("      \u23bf 回合未收敛：已达最大轮次"), texts(lines));
    }

    @Test
    @DisplayName("失败的回合补一行错误原因")
    void project_should_emit_error_notice_with_reason() {
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(),
                finished(InflightTurn.Outcome.ERROR, "连接超时"));

        assertEquals(Collections.singletonList("      \u23bf 回合失败：连接超时"), texts(lines));
    }

    @Test
    @DisplayName("失败但无原因时也不显示 null")
    void project_should_emit_error_notice_without_reason() {
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(),
                finished(InflightTurn.Outcome.ERROR, null));

        assertEquals(Collections.singletonList("      \u23bf 回合失败"), texts(lines));
    }

    @Test
    @DisplayName("正常结束时回归历史，不额外补行")
    void project_should_not_add_notice_when_completed() {
        List<VisualLine> lines = project(
                Collections.singletonList(SessionMessage.of(LlmMessage.assistant("完了"))),
                completed());

        assertEquals(Arrays.asList("", "  \u23fa jellyfish", "    完了"), texts(lines));
    }

    @Test
    @DisplayName("超过消息上限时只投影最近若干条，并在顶部提示折叠了多少条")
    void project_should_fold_old_messages_when_over_limit() {
        List<SessionMessage> messages = new ArrayList<SessionMessage>();
        for (int i = 0; i < 5; i++) {
            messages.add(SessionMessage.of(LlmMessage.user("第 " + i + " 条")));
        }

        List<VisualLine> lines = project(messages, completed(), WIDE, 2);

        assertEquals("      \u23bf 3 条更早的消息已折叠", lines.get(0).text());
        List<String> body = texts(lines);
        assertFalse(body.contains("  \u276f 第 0 条"), "折叠掉的消息不应出现");
        assertTrue(body.contains("  \u276f 第 4 条"), "最新的消息必须出现");
    }

    @Test
    @DisplayName("消息条数未超上限时不出现折叠提示")
    void project_should_not_fold_when_within_limit() {
        List<VisualLine> lines = project(
                Collections.singletonList(SessionMessage.of(LlmMessage.user("只有一条"))),
                completed(), WIDE, 10);

        assertFalse(texts(lines).get(0).contains("已折叠"));
    }

    @Test
    @DisplayName("系统角色消息不进投影，避免把系统提示词画到用户眼前")
    void project_should_ignore_system_role_message() {
        List<VisualLine> lines = project(
                Collections.singletonList(SessionMessage.of(LlmMessage.system("你是助手"))),
                completed());

        assertFalse(texts(lines).contains("你是助手"));
    }

    @Test
    @DisplayName("中文长正文按显示宽度换行，续行缩进与正文一致")
    void project_should_wrap_cjk_body_with_body_indent() {
        List<VisualLine> lines = project(
                Collections.singletonList(SessionMessage.of(LlmMessage.assistant("一二三四五六"))),
                completed(), 10, TranscriptProjector.DEFAULT_MAX_MESSAGES);

        assertEquals(Arrays.asList("", "  \u23fa jellyfish", "    一二三", "    四五六"), texts(lines));
    }

    @Test
    @DisplayName("正文超时被截断的回合补一行说明")
    void project_should_flag_truncated_text() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        // 直接把截断状态做出来：反复追加到超过上限
        for (int i = 0; i < InflightTurn.MAX_TEXT_CHARS; i += 1024) {
            turn.appendText(repeat("y", 1024));
        }
        // 再多一个字，越过上限以触发截断标记
        turn.appendText("z");
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertTrue(texts(lines).contains("      \u23bf 内容过长，仅显示末尾"));
    }

    @Test
    @DisplayName("命令输出按其发生时刻插进消息之间，而不是贴在投影末尾")
    void project_should_place_notice_at_its_timestamp() {
        List<SessionMessage> messages = Arrays.asList(
                message("m1", 1000L, LlmMessage.user("先问")),
                message("m2", 3000L, LlmMessage.assistant("后答")));
        List<ShellNotice> notices = Collections.singletonList(
                new ShellNotice(2000L, "/help", "/help 输出", ShellNotice.Kind.INFO));

        List<VisualLine> lines = project(messages, notices, completed());

        assertEquals(Arrays.asList(
                "", "  \u276f 先问",
                "", "  \u276f /help", "    \u23bf /help 输出",
                "", "  \u23fa jellyfish", "    后答"), texts(lines));
    }

    @Test
    @DisplayName("晚于最后一条消息的命令输出仍排在会话之后")
    void project_should_append_notice_when_after_last_message() {
        List<SessionMessage> messages = Collections.singletonList(
                message("m1", 1000L, LlmMessage.user("你好")));
        List<ShellNotice> notices = Collections.singletonList(
                new ShellNotice(2000L, null, "/status 输出", ShellNotice.Kind.INFO));

        assertEquals(Arrays.asList("", "  \u276f 你好", "", "    \u23bf /status 输出"),
                texts(project(messages, notices, completed())));
    }

    @Test
    @DisplayName("时间戳相同时消息在前：提示是对刚发生的事的反馈")
    void project_should_put_message_before_notice_when_same_timestamp() {
        List<SessionMessage> messages = Collections.singletonList(
                message("m1", 1000L, LlmMessage.user("你好")));
        List<ShellNotice> notices = Collections.singletonList(
                new ShellNotice(1000L, null, "/status 输出", ShellNotice.Kind.INFO));

        assertEquals(Arrays.asList("", "  \u276f 你好", "", "    \u23bf /status 输出"),
                texts(project(messages, notices, completed())));
    }

    @Test
    @DisplayName("多行输出只在首行带前缀，续行悬挂缩进且保留原始缩进")
    void project_should_hangIndent_notice_continuation_lines() {
        List<ShellNotice> notices = Collections.singletonList(new ShellNotice(1000L, null,
                "可用命令（2 条）：\n  /agent  切换 agent\n  /help   查看帮助", ShellNotice.Kind.INFO));

        assertEquals(Arrays.asList(
                "",
                "    \u23bf 可用命令（2 条）：",
                "        /agent  切换 agent",
                "        /help   查看帮助"),
                texts(project(Collections.<SessionMessage>emptyList(), notices, completed())));
    }

    @Test
    @DisplayName("命令原文回显成一行用户消息，回答「这是哪条命令的输出」")
    void project_should_echo_command_line() {
        List<ShellNotice> notices = Collections.singletonList(
                new ShellNotice(1000L, "/model openai/gpt-4o", "已切换模型", ShellNotice.Kind.INFO));

        assertEquals(Arrays.asList("", "  \u276f /model openai/gpt-4o", "    \u23bf 已切换模型"),
                texts(project(Collections.<SessionMessage>emptyList(), notices, completed())));
    }

    @Test
    @DisplayName("未知命令用警示前缀，失败用错误前缀，两者与正常输出区分开")
    void project_should_mark_notice_kind_with_distinct_prefix() {
        List<ShellNotice> notices = Arrays.asList(
                new ShellNotice(1000L, null, "正常", ShellNotice.Kind.INFO),
                new ShellNotice(2000L, null, "未知命令", ShellNotice.Kind.WARN),
                new ShellNotice(3000L, null, "失败", ShellNotice.Kind.ERROR));

        List<String> body = texts(project(Collections.<SessionMessage>emptyList(), notices, completed()));

        assertTrue(body.contains("    \u23bf 正常"), "正常提示应用普通前缀");
        assertTrue(body.contains("    ! 未知命令"), "警示提示应用 ! 前缀");
        assertTrue(body.contains("    \u2717 失败"), "错误提示应用 ✗ 前缀");
    }

    @Test
    @DisplayName("长行自动换行的续行与块前缀等宽对齐")
    void project_should_align_wrapped_notice_line_with_block_indent() {
        List<ShellNotice> notices = Collections.singletonList(new ShellNotice(1000L, null,
                "一二三四五六七八九十一二三四五六七八九十", ShellNotice.Kind.INFO));

        List<String> body = texts(project(Collections.<SessionMessage>emptyList(), notices, completed(), 16,
                TranscriptProjector.DEFAULT_MAX_MESSAGES));

        assertEquals(5, body.size(), "空行 + 四条换行后的视觉行，实际：" + body);
        assertTrue(body.get(1).startsWith("    \u23bf "), "首行带块前缀，实际：" + body.get(1));
        assertTrue(body.get(2).startsWith("      "), "续行应是 6 列纯缩进，实际：" + body.get(2));
        assertFalse(body.get(2).contains("\u23bf"), "续行不得重复块前缀，实际：" + body.get(2));
    }

    @Test
    @DisplayName("比投影窗口更旧的外壳提示被丢弃，不会出现在折叠行之前")
    void project_should_drop_notice_older_than_window() {
        List<SessionMessage> messages = new ArrayList<SessionMessage>();
        for (int i = 0; i < 5; i++) {
            messages.add(message("m" + i, 1000L + i * 1000L, LlmMessage.user("第 " + i + " 条")));
        }
        // 窗口只留最后两条（时间戳 4000/5000），这条提示落在窗口之前
        List<ShellNotice> notices = Collections.singletonList(
                new ShellNotice(2000L, null, "旧命令输出", ShellNotice.Kind.INFO));

        List<String> body = texts(project(messages, notices, completed(), WIDE, 2));

        assertFalse(body.contains("    \u23bf 旧命令输出"), "窗口之前的提示不应出现");
    }

    @Test
    @DisplayName("窗口内的外壳提示仍按时间戳归并")
    void project_should_keep_notice_inside_window() {
        List<SessionMessage> messages = new ArrayList<SessionMessage>();
        for (int i = 0; i < 5; i++) {
            messages.add(message("m" + i, 1000L + i * 1000L, LlmMessage.user("第 " + i + " 条")));
        }
        List<ShellNotice> notices = Collections.singletonList(
                new ShellNotice(4500L, null, "窗口内命令输出", ShellNotice.Kind.INFO));

        List<String> body = texts(project(messages, notices, completed(), WIDE, 2));

        assertTrue(body.contains("    \u23bf 窗口内命令输出"), "窗口内的提示必须出现");
    }

    @Test
    @DisplayName("首页投影：居中字标，不带消息表头")
    void home_should_renderCenteredLogo() {
        List<String> body = texts(TranscriptProjector.home(Collections.<ShellNotice>emptyList(), WIDE));

        assertEquals(2, body.size(), "空行 + 字标，实际：" + body);
        assertEquals("", body.get(0));
        assertEquals(HomeSplash.LOGO, body.get(1).trim());
        assertTrue(body.get(1).startsWith(" "), "必须居中，实际：" + body);
    }

    @Test
    @DisplayName("首页投影：外壳提示仍然可见（如 /resume 报错）")
    void home_should_renderNotices() {
        ShellNotice notice = new ShellNotice(1L, "/resume missing", "会话不存在：missing", ShellNotice.Kind.ERROR);
        List<String> body = texts(TranscriptProjector.home(Collections.singletonList(notice), WIDE));

        assertTrue(body.contains("    \u2717 会话不存在：missing"), "首页上必须能看到错误提示，实际：" + body);
    }

    /**
     * 执行一次投影。
     *
     * @param messages 消息列表
     * @param inflight 暂存区快照
     * @return 视觉行列表
     */
    private static List<VisualLine> project(List<SessionMessage> messages, InflightTurn.Snapshot inflight) {
        return project(messages, inflight, WIDE, TranscriptProjector.DEFAULT_MAX_MESSAGES);
    }

    /**
     * 执行一次带外壳提示的投影。
     *
     * @param messages 消息列表
     * @param notices  外壳提示列表
     * @param inflight 暂存区快照
     * @return 视觉行列表
     */
    private static List<VisualLine> project(List<SessionMessage> messages, List<ShellNotice> notices,
                                            InflightTurn.Snapshot inflight) {
        return TranscriptProjector.project(messages, notices, inflight, WIDE,
                TranscriptProjector.DEFAULT_MAX_MESSAGES);
    }

    /**
     * 执行一次投影。
     *
     * @param messages    消息列表
     * @param inflight    暂存区快照
     * @param width       可用列数
     * @param maxMessages 消息上限
     * @return 视觉行列表
     */
    private static List<VisualLine> project(List<SessionMessage> messages, InflightTurn.Snapshot inflight,
                                            int width, int maxMessages) {
        return project(messages, Collections.<ShellNotice>emptyList(), inflight, width, maxMessages);
    }

    /**
     * 执行一次带外壳提示的投影。
     *
     * @param messages    消息列表
     * @param notices     外壳提示列表
     * @param inflight    暂存区快照
     * @param width       可用列数
     * @param maxMessages 消息上限
     * @return 视觉行列表
     */
    private static List<VisualLine> project(List<SessionMessage> messages, List<ShellNotice> notices,
                                            InflightTurn.Snapshot inflight, int width, int maxMessages) {
        return TranscriptProjector.project(messages, notices, inflight, width, maxMessages);
    }

    /**
     * 构造一条带显式时间戳的会话消息。
     *
     * @param id        消息标识
     * @param timestamp 时间戳
     * @param body      消息本体
     * @return 会话消息
     */
    private static SessionMessage message(String id, long timestamp, LlmMessage body) {
        return new SessionMessage(id, timestamp, body, null);
    }

    /**
     * 构造进行中回合的快照。
     *
     * @param text     正文
     * @param thinking 思考过程
     * @return 快照
     */
    private static InflightTurn.Snapshot running(String text, String thinking) {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.appendText(text);
        turn.appendThinking(thinking);
        return turn.snapshot();
    }

    /**
     * 构造已正常结束的快照。
     *
     * @return 快照
     */
    private static InflightTurn.Snapshot completed() {
        return finished(InflightTurn.Outcome.COMPLETED, null);
    }

    /**
     * 构造带指定终局的快照。
     *
     * @param outcome      终局
     * @param errorMessage 失败原因
     * @return 快照
     */
    private static InflightTurn.Snapshot finished(InflightTurn.Outcome outcome, String errorMessage) {
        InflightTurn turn = new InflightTurn();
        turn.finish(outcome, errorMessage);
        return turn.snapshot();
    }

    /**
     * 抽取每行的纯文本。
     *
     * @param lines 视觉行
     * @return 纯文本列表
     */
    private static List<String> texts(List<VisualLine> lines) {
        List<String> result = new ArrayList<String>(lines.size());
        for (VisualLine line : lines) {
            result.add(line.text());
        }
        return result;
    }

    /**
     * 生成重复字符串。
     *
     * @param unit  单元
     * @param times 次数
     * @return 重复后的字符串
     */
    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
