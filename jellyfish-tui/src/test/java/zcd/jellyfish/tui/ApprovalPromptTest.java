package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApprovalPrompt} 的单元测试：钉住「审批框里显示的东西就是工具将要做的事」这条前提。
 * <p>
 * 断言全部基于纯文本（{@link VisualLine#text()}），因此不依赖终端，也不受样式改动影响。
 *
 * @author zcd
 */
@DisplayName("ApprovalPrompt 审批浮层")
class ApprovalPromptTest {

    /** 测试用列数。 */
    private static final int WIDTH = 60;

    @Test
    @DisplayName("选项固定为「允许一次 / 拒绝」，首项默认选中")
    void choices_should_defaultToAllow() {
        // When
        List<CommandChoice> choices = ApprovalPrompt.choices();

        // Then
        assertEquals(2, choices.size());
        assertEquals(ApprovalPrompt.ALLOW, choices.get(0).getValue());
        assertEquals("允许一次", choices.get(0).getLabel());
        assertEquals(ApprovalPrompt.DENY, choices.get(1).getValue());
        assertTrue(!choices.get(0).isCurrent() && !choices.get(1).isCurrent(), "审批没有「当前取值」这回事");
    }

    @Test
    @DisplayName("选项默认选中「允许一次」：审批的语义是等人补一句确认")
    void picker_should_selectAllowByDefault() {
        // Given
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("", ApprovalPrompt.choices());

        // Then
        assertEquals(ApprovalPrompt.ALLOW, picker.selected().getValue());
        picker.moveDown();
        assertEquals(ApprovalPrompt.DENY, picker.selected().getValue());
        picker.moveDown();
        assertEquals(ApprovalPrompt.ALLOW, picker.selected().getValue(), "到尾后应回到首项");
    }

    @Test
    @DisplayName("只有「允许一次」算批准；认不出来的取值一律按拒绝処理")
    void isApproved_should_onlyAcceptExplicitAllow() {
        assertEquals(true, ApprovalPrompt.isApproved(new CommandChoice(ApprovalPrompt.ALLOW, "允许一次")));
        assertEquals(false, ApprovalPrompt.isApproved(new CommandChoice(ApprovalPrompt.DENY, "拒绝")));
        assertEquals(false, ApprovalPrompt.isApproved(new CommandChoice("whatever", "未知")));
        assertEquals(false, ApprovalPrompt.isApproved(null));
    }

    @Test
    @DisplayName("字段区给出工具、参数、理由与会话上下文")
    void render_should_showToolArgumentsAndContext() {
        // Given
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "src/main/App.java");
        ApprovalChannel.Pending pending = pending("write_file", arguments, PermissionMode.PLAN);

        // When
        List<String> body = texts(render(pending, activePicker()));

        // Then
        assertTrue(body.get(0).startsWith(" 工具 write_file"), body.toString());
        assertTrue(body.get(1).contains("\"path\": \"src/main/App.java\""), body.toString());
        assertTrue(body.stream().anyMatch(line -> line.contains("策略要求人工审批")), body.toString());
        assertTrue(body.stream().anyMatch(line -> line.contains("模式 plan")), body.toString());
    }

    @Test
    @DisplayName("敏感键脱敏：apiKey / token 只显示 ***")
    void render_should_maskSensitiveArguments() {
        // Given
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("apiKey", "sk-real-secret");
        arguments.put("Authorization-Token", "bearer-abc");
        arguments.put("key", "plain-key");
        arguments.put("path", "a.txt");

        // When
        String body = String.join("\n", texts(render(pending("bash", arguments, null), activePicker())));

        // Then
        assertFalse(body.contains("sk-real-secret"), body);
        assertFalse(body.contains("bearer-abc"), body);
        assertFalse(body.contains("plain-key"), body);
        assertTrue(body.contains("***"), body);
        assertTrue(body.contains("a.txt"), "非敏感参数必须照常显示");
    }

    @Test
    @DisplayName("参数空时显示占位而不是空括号")
    void render_should_showPlaceholder_when_noArguments() {
        // When
        List<String> body = texts(render(pending("list_dir", null, null), activePicker()));

        // Then
        assertTrue(body.get(1).contains("-"), body.toString());
    }

    @Test
    @DisplayName("控制字符与控制序列被剔除：参数不能改写屏幕")
    void render_should_stripControlCharacters() {
        // Given：ESC 清屏 + 回车改写 + 双向控制符
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "a\u001b[2Jb\rc\u202ed.txt");

        // When
        String body = String.join("", texts(render(pending("write_file", arguments, null), activePicker())));

        // Then
        assertFalse(body.contains("\u001b"), "ESC 必须被剔除");
        assertFalse(body.contains("\r"), "回车必须被剔除");
        assertFalse(body.contains("\u202e"), "双向控制符必须被剔除");
        assertTrue(body.contains("a[2Jbc") || body.contains("a[2Jb c"), body);
    }

    @Test
    @DisplayName("超长参数折行显示，到顶后明确写出省略了几行")
    void render_should_wrapAndLimitLongArguments() {
        // Given：一段远超 6 行的内容
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            content.append("行").append(i).append(' ');
        }
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("content", content.toString());

        // When
        List<String> body = texts(render(pending("write_file", arguments, null), activePicker()));

        // Then：省略提示出现，且没有任何一行超出可用列数
        assertTrue(body.stream().anyMatch(line -> line.contains("已省略")), body.toString());
        for (String line : body) {
            assertTrue(zcd.jellyfish.tui.text.DisplayWidth.of(line) <= WIDTH,
                    "宽'度超限：" + line);
        }
    }

    @Test
    @DisplayName("选择页未激活时不渲染选项区，只留字段区")
    void render_should_skipOptions_when_pickerInactive() {
        // When
        List<String> body = texts(ApprovalPrompt.render(pending("write_file", null, null),
                new CommandChoicePicker(), WIDTH));

        // Then
        assertFalse(body.stream().anyMatch(line -> line.contains("允许一次")), body.toString());
        assertFalse(body.stream().anyMatch(line -> line.contains("Enter 确认")), body.toString());
    }

    @Test
    @DisplayName("选项区的键位提示是审批语义：Esc 是「拒绝并中断」而不是「取消」")
    void render_should_useApprovalHint() {
        // When
        List<String> body = texts(render(pending("write_file", null, null), activePicker()));

        // Then
        assertTrue(body.stream().anyMatch(line -> line.contains("允许一次")), body.toString());
        assertTrue(body.stream().anyMatch(line -> line.contains("拒绝并中断")), body.toString());
    }

    @Test
    @DisplayName("请求为空时返回空列表，调用方据此不显示面板")
    void render_should_returnEmpty_when_pendingNull() {
        assertTrue(ApprovalPrompt.render(null, activePicker(), WIDTH).isEmpty());
    }

    @Test
    @DisplayName("无理由时不渲染理由行：空标签比少一行更让人困惑")
    void render_should_skipReasonLine_whenReasonBlank() {
        // When
        ApprovalChannel.Pending blank = new ApprovalChannel.Pending("a1b2c3d4e5f6", "agent-a",
                "write_file", null, PermissionMode.NORMAL, "  ");
        List<String> body = texts(render(blank, activePicker()));

        // Then
        assertFalse(body.stream().anyMatch(line -> line.startsWith(" 理由")), body.toString());
    }

    /**
     * 渲染一帧。
     *
     * @param pending 请求
     * @param picker  选项状态
     * @return 视觉行
     */
    private static List<VisualLine> render(ApprovalChannel.Pending pending, CommandChoicePicker picker) {
        return ApprovalPrompt.render(pending, picker, WIDTH);
    }

    /**
     * 构造一个已打开的选项状态。
     *
     * @return 选项状态
     */
    private static CommandChoicePicker activePicker() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("", ApprovalPrompt.choices());
        return picker;
    }

    /**
     * 构造一条待审批请求。
     *
     * @param toolName  工具名
     * @param arguments 参数
     * @param mode      权限模式，可为 {@code null}
     * @return 请求
     */
    private static ApprovalChannel.Pending pending(String toolName, Map<String, Object> arguments,
                                                   PermissionMode mode) {
        return new ApprovalChannel.Pending("a1b2c3d4e5f6", "agent-a", toolName, arguments, mode,
                "agent 策略要求人工审批该工具");
    }

    /**
     * 取视觉行的纯文本。
     *
     * @param lines 视觉行
     * @return 文本列表
     */
    private static List<String> texts(List<VisualLine> lines) {
        List<String> result = new ArrayList<String>(lines.size());
        for (VisualLine line : lines) {
            result.add(line.text());
        }
        return Collections.unmodifiableList(result);
    }
}
