package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    @DisplayName("助手正文按 markdown 渲染：标题带块前缀、行内标记换成样式而不是字面量")
    void project_should_render_assistant_body_as_markdown() {
        List<VisualLine> lines = project(
                Collections.singletonList(SessionMessage.of(
                        LlmMessage.assistant("## 小节\n\n结论是**加粗**的。"))),
                completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "    \u258c 小节",
                "",
                "    结论是加粗的。"), texts(lines));
        // 正文缩进仍然在：markdown 的块前缀接在消息缩进之后
        assertEquals("    \u258c 小节", texts(lines).get(2));
    }

    @Test
    @DisplayName("用户消息保持纯文本：用户输入的多是自然语言，渲染收益低且会吞掉原文空白")
    void project_should_keep_user_message_asPlainText() {
        List<VisualLine> lines = project(
                Collections.singletonList(SessionMessage.of(LlmMessage.user("## 这不是标题"))),
                completed());

        assertEquals(Arrays.asList("", "  \u276f ## 这不是标题"), texts(lines));
    }

    @Test
    @DisplayName("失败的命令要在轨迹上一眼看出：只有元数据能给出这个信号")
    void project_should_mark_failed_tool_from_metadata() {
        // Given：命令跑完了但退出码非零（界面上看不出「成没成」是这条轨迹最大的信息缺口）
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(1));
        List<SessionMessage> messages = Arrays.asList(
                SessionMessage.of(LlmMessage.assistant("跑一下", Collections.emptyList())),
                SessionMessage.ofTool(LlmMessage.tool("c1", "shell", "cwd: /x · exit: 1"), metadata));

        // When
        List<VisualLine> lines = project(messages, completed());

        // Then：红色后缀接在工具名之后（判据来自字段，不解析首行文案）
        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "    跑一下",
                "      \u23bf shell \u26a0 退出码 1"), texts(lines));
    }

    @Test
    @DisplayName("被终止的命令（超时 / 取消）同样要标出来：那正是「为什么没有输出」的答案")
    void project_should_mark_terminated_tool() {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_TERMINAL, "TIMEOUT");
        List<SessionMessage> messages = Collections.singletonList(
                SessionMessage.ofTool(LlmMessage.tool("c1", "shell", "已超时"), metadata));

        List<VisualLine> lines = project(messages, completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf shell \u26a0 TIMEOUT"), texts(lines));
    }

    @Test
    @DisplayName("工具抛异常：metadata 带 FAILED 与原因首行时渲染出警示与原因")
    void project_should_mark_failed_tool_with_reason() {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_TERMINAL, "FAILED");
        metadata.put(ToolMetadata.KEY_SUMMARY, "文件不存在: /x/y");
        List<SessionMessage> messages = Collections.singletonList(
                SessionMessage.ofTool(LlmMessage.tool("c1", "read_file", "工具执行失败：文件不存在: /x/y"), metadata));

        List<VisualLine> lines = project(messages, completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf read_file \u00b7 文件不存在: /x/y \u26a0 FAILED"), texts(lines));
    }

    @Test
    @DisplayName("成功的命令不加任何后缀：成功是常态，标出来只会埋掉需要看见的那几条")
    void project_should_notMark_successfulTool() {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(0));
        metadata.put(ToolMetadata.KEY_TERMINAL, ToolMetadata.TERMINAL_COMPLETED);
        List<SessionMessage> messages = Collections.singletonList(
                SessionMessage.ofTool(LlmMessage.tool("c1", "shell", "cwd: /x · exit: 0"), metadata));

        List<VisualLine> lines = project(messages, completed());

        assertFalse(texts(lines).toString().contains(TranscriptProjector.WARNING_MARK), texts(lines).toString());
    }

    @Test
    @DisplayName("单行摘要接在工具名之后：否则一层 `⎿ task` 什么也回答不了")
    void project_should_show_summary_from_metadata() {
        // Given：子代理的结论正文不进消息区（那是回灌给模型的长文本），
        // 因此「刚才那一行到底是什么事」只能靠摘要
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_SUMMARY, "子代理 scout · 3 轮");
        List<SessionMessage> messages = Collections.singletonList(
                SessionMessage.ofTool(LlmMessage.tool("c1", "task", "[子代理 scout 已完成 · 3 轮]\n报告正文"),
                        metadata));

        // When
        List<VisualLine> lines = project(messages, completed());

        // Then
        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf task \u00b7 子代理 scout \u00b7 3 轮"), texts(lines));
    }

    @Test
    @DisplayName("摘要与警示后缀共存：先读「是什么事」，再读「成没成」")
    void project_should_show_summary_before_failure_suffix() {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_SUMMARY, "子代理 scout");
        metadata.put(ToolMetadata.KEY_TERMINAL, "REJECTED");
        List<SessionMessage> messages = Collections.singletonList(
                SessionMessage.ofTool(LlmMessage.tool("c1", "task", "[子代理未开始] 未知类型"), metadata));

        List<VisualLine> lines = project(messages, completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf task \u00b7 子代理 scout \u26a0 REJECTED"), texts(lines));
    }

    @Test
    @DisplayName("没有摘要时轨迹行与以前一模一样：不给普通工具多出一个空尾巴")
    void project_should_omit_summary_when_absent() {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put("durationMs", 12L);
        List<SessionMessage> messages = Collections.singletonList(
                SessionMessage.ofTool(LlmMessage.tool("c1", "read_file", "内容"), metadata));

        List<VisualLine> lines = project(messages, completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf read_file"), texts(lines));
    }

    @Test
    @DisplayName("工具轨迹不受 markdown 渲染影响：它是轨迹，不是回答")
    void project_should_keep_tool_trace_untouched() {
        List<SessionMessage> messages = Arrays.asList(
                SessionMessage.of(LlmMessage.assistant("**看**一下", Collections.emptyList())),
                SessionMessage.of(LlmMessage.tool("c1", "read_file", "**内容**")));

        List<VisualLine> lines = project(messages, completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "    看一下",
                "      \u23bf read_file"), texts(lines));
    }

    @Test
    @DisplayName("进行中回合的正文同样按 markdown 渲染")
    void project_should_render_inflight_body_as_markdown() {
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(),
                running("- 第一项", ""));

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "    \u2022 第一项"), texts(lines));
    }

    @Test
    @DisplayName("markdown 渲染后每行仍不超过消息区宽度：越界会让终端自行折行、滚动随之错位")
    void project_should_neverExceed_width_when_renderingMarkdown() {
        // Given：一段含长段落与代码块的助手消息，以及一个窄窗口
        SessionMessage message = SessionMessage.of(LlmMessage.assistant(
                "一段很长的中文说明文字，用来验证按列数换行。\n\n"
                        + "```java\nint a = 1; // 这一行代码相当长，超出可用宽度\n```\n\n"
                        + "- 列表项里也有很长的一段中文，还会带一个地址 http://example.com/a/b/c\n"));

        for (int width : new int[]{20, 40, 79}) {
            // When
            List<VisualLine> lines = project(Collections.singletonList(message), completed(), width, 500);

            // Then
            for (VisualLine line : lines) {
                assertTrue(line.width() <= width, "宽 " + width + " 下越界：" + line.text());
            }
        }
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
        List<VisualLine> lines = projectExpanded(Collections.<SessionMessage>emptyList(),
                running("答案是 42", "用户想算加法"));

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u273b 用户想算加法",
                "    答案是 42"), texts(lines));
    }

    @Test
    @DisplayName("默认折叠：历史里的思考压成一行并报码点数")
    void project_should_fold_history_thinking_by_default() {
        SessionMessage message = SessionMessage.of(LlmMessage.assistant("答案 42"), null, "先算一加一");

        List<VisualLine> lines = project(Collections.singletonList(message), completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u273b 思考过程（5 字，Ctrl+T 展开）",
                "    答案 42"), texts(lines));
    }

    @Test
    @DisplayName("展开态：历史里的思考铺全部内容，思考仍在正文之前")
    void project_should_expand_history_thinking_when_toggled() {
        SessionMessage message = SessionMessage.of(LlmMessage.assistant("答案 42"), null, "先算一加一");

        List<VisualLine> lines = projectExpanded(Collections.singletonList(message), completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u273b 先算一加一",
                "    答案 42"), texts(lines));
    }

    @Test
    @DisplayName("折叠时仍能看出模型正在思考，并给出已产出的字数")
    void project_should_fold_inflight_thinking_with_count() {
        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(),
                running("答案 42", "用户想算加法"));

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u273b 思考中\u2026（6 字）",
                "    答案 42"), texts(lines));
    }

    @Test
    @DisplayName("只有思考、没有正文的助手消息也占一个块，不因正文为空而整条消失")
    void project_should_render_assistant_message_with_only_thinking() {
        SessionMessage message = SessionMessage.of(LlmMessage.assistant(""), null, "还没想好");

        List<VisualLine> lines = project(Collections.singletonList(message), completed());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u273b 思考过程（4 字，Ctrl+T 展开）"), texts(lines));
    }

    @Test
    @DisplayName("思考字数按码点数：代理对算一个字符")
    void project_should_count_thinking_by_codePoint() {
        SessionMessage message = SessionMessage.of(LlmMessage.assistant("好"), null, "\ud83d\ude00\ud83d\ude01");

        List<VisualLine> lines = project(Collections.singletonList(message), completed());

        assertTrue(texts(lines).contains("      \u273b 思考过程（2 字，Ctrl+T 展开）"),
                "实际：" + texts(lines));
    }

    @Test
    @DisplayName("空白思考不占行：nil 与全空白同等处理")
    void project_should_skip_blank_thinking() {
        SessionMessage message = SessionMessage.of(LlmMessage.assistant("答案"), null, "   ");

        List<VisualLine> lines = project(Collections.singletonList(message), completed());

        assertEquals(Arrays.asList("", "  \u23fa jellyfish", "    答案"), texts(lines));
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
    @DisplayName("首页投影：字标与引导提示按视口高度垂直居中，不带消息表头")
    void home_should_centerLogoVertically() {
        int viewportRows = 21;
        List<String> body = texts(TranscriptProjector.home(
                Collections.<ShellNotice>emptyList(), WIDE, viewportRows));

        int contentRows = 5 + HomeHints.LEADING_BLANK_ROWS + HomeHints.HINTS.length;
        int blankAbove = blankRowsAbove(body);
        assertEquals(contentRows, body.size() - blankAbove, "内容行数（图案 + 空行 + 提示），实际：" + body);
        // 留白只补在上方，下方那半由视口剩余空间担任；可用空间为奇数行时上少下多
        assertTrue(Math.abs(blankAbove - (viewportRows - contentRows - blankAbove)) <= 1,
                "上下留白必须对称，实际：" + body);
        assertTrue(body.get(blankAbove).indexOf('\u2588') >= 0, "字标必须画出来，实际：" + body);
        assertTrue(hasLineContaining(body, HomeHints.HINTS[0]), "引导提示必须在字标下方，实际：" + body);
    }

    @Test
    @DisplayName("首页投影：图案放不下时退回单行文本，提示照旧")
    void home_should_fallBackToSingleLine_when_narrow() {
        List<String> body = texts(TranscriptProjector.home(
                Collections.<ShellNotice>emptyList(), 40, 21));

        int blankAbove = blankRowsAbove(body);
        assertEquals(HomeSplash.LOGO, body.get(blankAbove).trim(), "实际：" + body);
        assertFalse(body.get(blankAbove).contains("\u2588"), "40 列放不下 53 列图案：" + body);
        assertTrue(hasLineContaining(body, HomeHints.HINTS[0]), "提示放得下就该显示，实际：" + body);
    }

    @Test
    @DisplayName("首页投影：窄到提示也放不下时只剩字标，不吐半截提示")
    void home_should_dropHints_when_extremelyNarrow() {
        List<String> body = texts(TranscriptProjector.home(
                Collections.<ShellNotice>emptyList(), 30, 21));

        int blankAbove = blankRowsAbove(body);
        assertEquals(1, body.size() - blankAbove, "只剩一行字标，实际：" + body);
        assertEquals(HomeSplash.LOGO, body.get(blankAbove).trim());
        assertFalse(hasLineContaining(body, HomeHints.HINTS[0]), "提示放不下就该整行丢弃：" + body);
    }

    @Test
    @DisplayName("首页投影：内容比视口高时只留一行顶距，不居中")
    void home_should_keepSingleBlankRow_when_contentTallerThanViewport() {
        List<ShellNotice> notices = new ArrayList<ShellNotice>();
        for (int i = 0; i < 10; i++) {
            notices.add(new ShellNotice(i, "/resume missing", "会话不存在：" + i, ShellNotice.Kind.ERROR));
        }

        List<String> body = texts(TranscriptProjector.home(notices, WIDE, 4));

        assertEquals("", body.get(0), "字标不能贴着上边框，实际：" + body);
        assertTrue(body.size() > 5, "10 条提示不可能被压进 4 行，实际：" + body);
    }

    @Test
    @DisplayName("首页投影：外壳提示仍然可见（如 /resume 报错）")
    void home_should_renderNotices() {
        ShellNotice notice = new ShellNotice(1L, "/resume missing", "会话不存在：missing", ShellNotice.Kind.ERROR);
        List<String> body = texts(TranscriptProjector.home(Collections.singletonList(notice), WIDE, 21));

        assertTrue(body.contains("    \u2717 会话不存在：missing"), "首页上必须能看到错误提示，实际：" + body);
    }

    /**
     * 数出顶部连续空行的数量。
     *
     * @param body 投影结果
     * @return 顶部空行数
     */
    private static int blankRowsAbove(List<String> body) {
        int blank = 0;
        while (blank < body.size() && body.get(blank).isEmpty()) {
            blank++;
        }
        return blank;
    }

    /**
     * 判断是否有某一行包含指定片段。
     * <p>
     * 不能用 {@code List.contains}：居中后的行带前导空格，那是整行精确匹配。
     *
     * @param body     投影结果
     * @param fragment 片段
     * @return 命中返回 {@code true}
     */
    private static boolean hasLineContaining(List<String> body, String fragment) {
        for (String line : body) {
            if (line.contains(fragment)) {
                return true;
            }
        }
        return false;
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
        return project(messages, notices, inflight, WIDE, TranscriptProjector.DEFAULT_MAX_MESSAGES);
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
        return TranscriptProjector.project(messages, notices, inflight, width, maxMessages, false);
    }

    /**
     * 以「思考过程已展开」执行一次投影。
     *
     * @param messages 消息列表
     * @param inflight 暂存区快照
     * @return 视觉行列表
     */
    private static List<VisualLine> projectExpanded(List<SessionMessage> messages,
                                                    InflightTurn.Snapshot inflight) {
        return TranscriptProjector.project(messages, Collections.<ShellNotice>emptyList(), inflight, WIDE,
                TranscriptProjector.DEFAULT_MAX_MESSAGES, true);
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

    @Test
    @DisplayName("工具在跑时显示它的名字与实时输出，而不是「处理中…」")
    void project_should_render_running_tool_output() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.appendText("我先跑个命令");
        turn.clearText();
        turn.beginTool("bash");
        turn.appendToolOutput("构建中\n完成\n");

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf bash",
                "      \u2502 构建中",
                "      \u2502 完成"), texts(lines));
    }

    @Test
    @DisplayName("工具还没输出时也先显示名字——否则那段时间与卡死没两样")
    void project_should_render_tool_name_before_output() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.beginTool("bash");

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertEquals(Arrays.asList("", "  \u23fa jellyfish", "      \u23bf bash"), texts(lines));
    }

    @Test
    @DisplayName("工具在跑时显示目标：参数来自模型，是返回前唯一能说明「在动什么」的输入")
    void project_should_render_running_tool_target_from_arguments() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "a.txt");
        turn.beginTool("read_file", arguments);

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf read_file \u00b7 {\"path\": \"a.txt\"}"), texts(lines));
    }

    @Test
    @DisplayName("没有参数时轨迹行与以前一模一样——不给普通工具多出一个空尾巴")
    void project_should_render_tool_name_without_target_when_arguments_empty() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.beginTool("read_file", Collections.<String, Object>emptyMap());

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertEquals(Arrays.asList("", "  \u23fa jellyfish", "      \u23bf read_file"), texts(lines));
    }

    @Test
    @DisplayName("运行中目标按显示列截断并保持单行：全角字符不能当码点算")
    void project_should_truncate_long_running_tool_target_to_single_line() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("content", repeat("中", 200));
        turn.beginTool("write_file", arguments);

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        // 空行 + 表头 + 恰好一行轨迹；折行就说明没按列算
        assertEquals(3, lines.size(), texts(lines).toString());
        String label = texts(lines).get(2);
        assertTrue(label.startsWith("      \u23bf write_file \u00b7 "), label);
        assertTrue(label.endsWith("\u2026"), label);
    }

    @Test
    @DisplayName("运行中目标里的控制字符与换行被滤掉——单行标签不能被 content 折成几百行")
    void project_should_strip_control_chars_and_newlines_from_running_tool_target() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("content", "\u001b[2J危险\n\n下一行");
        turn.beginTool("write_file", arguments);

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertEquals(3, lines.size(), texts(lines).toString());
        String label = texts(lines).get(2);
        assertFalse(label.contains("\u001b"), label);
        assertFalse(label.contains("\n"), label);
        assertTrue(label.contains("危险"), label);
    }

    @Test
    @DisplayName("运行中目标对敏感参数脱敏——与审批浮层同一口径")
    void project_should_mask_sensitive_arguments_in_running_tool_target() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("apiKey", "sk-secret");
        turn.beginTool("call", arguments);

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        String label = texts(lines).get(2);
        assertFalse(label.contains("sk-secret"), label);
        assertTrue(label.contains("\"***\""), label);
    }

    @Test
    @DisplayName("已在助手块内时不重复打表头——上一轮 assistant 消息已经打过了")
    void project_should_not_repeat_header_for_running_tool() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.beginTool("bash");
        turn.appendToolOutput("输出\n");
        List<SessionMessage> messages = Collections.singletonList(
                SessionMessage.of(LlmMessage.assistant("", Collections.emptyList())));

        List<VisualLine> lines = project(messages, turn.snapshot());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf bash",
                "      \u2502 输出"), texts(lines));
    }

    @Test
    @DisplayName("规则 1 优先：模型又开始说话时实时输出不再占据屏幕")
    void project_should_prefer_streaming_text_over_tool_output() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.beginTool("bash");
        turn.appendToolOutput("输出\n");
        turn.appendText("模型继续说");

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertFalse(lineTexts(lines).contains("      \u2502 输出"), lineTexts(lines).toString());
        assertTrue(lineTexts(lines).contains("    模型继续说"), lineTexts(lines).toString());
    }

    @Test
    @DisplayName("实时输出里的控制字符被滤掉——一段 ESC 序列就能改写屏幕")
    void project_should_strip_control_chars_from_tool_output() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.beginTool("bash");
        turn.appendToolOutput("\u001b[2J危险\r\n");

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf bash",
                "      \u2502 [2J危险"), texts(lines));
    }

    @Test
    @DisplayName("实时输出的空行不留下竖线，只留一个空行")
    void project_should_render_blank_tool_line_as_empty() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.beginTool("bash");
        turn.appendToolOutput("a\n\nb\n");

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());

        assertEquals(Arrays.asList(
                "",
                "  \u23fa jellyfish",
                "      \u23bf bash",
                "      \u2502 a",
                "",
                "      \u2502 b"), texts(lines));
    }

    @Test
    @DisplayName("清掉实时输出后回到「处理中…」")
    void project_should_fall_back_to_pending_when_tool_output_cleared() {
        InflightTurn turn = new InflightTurn();
        turn.begin();
        turn.beginTool("bash");
        turn.appendToolOutput("输出\n");

        turn.clearToolOutput();

        List<VisualLine> lines = project(Collections.<SessionMessage>emptyList(), turn.snapshot());
        assertEquals(Collections.singletonList("      \u23bf 处理中\u2026"), texts(lines));
    }

    /**
     * 抽取每行的纯文本（便于用 assertTrue 做包含断言）。
     *
     * @param lines 视觉行
     * @return 纯文本列表
     */
    private static List<String> lineTexts(List<VisualLine> lines) {
        return texts(lines);
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
