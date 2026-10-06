package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskPrompt} 的单元测试：钉住「问题优先、候选其次，并且永远留一个自己写的出口」。
 * <p>
 * 断言全部基于纯文本（{@link VisualLine#text()}），因此不依赖终端，也不受样式改动影响。
 *
 * @author zcd
 */
@DisplayName("AskPrompt 提问浮层")
class AskPromptTest {

    /** 测试用列数。 */
    private static final int WIDTH = 60;

    @Test
    @DisplayName("候选项之后固定追加一项「其它（自己输入）」")
    void choices_should_appendCustomOptionLast() {
        // When
        List<CommandChoice> choices = AskPrompt.choices(request());

        // Then
        assertEquals(3, choices.size());
        assertEquals("a", choices.get(0).getValue());
        assertEquals("先做通道", choices.get(0).getLabel());
        assertEquals("内核与插件先落地", choices.get(0).getDescription());
        assertEquals(AskPrompt.OTHER, choices.get(2).getValue());
        assertTrue(choices.get(2).getLabel().contains("其它"), choices.get(2).getLabel());
    }

    @Test
    @DisplayName("默认选中用户给的第一个选项，而不是「其它」")
    void picker_should_selectFirstUserOptionByDefault() {
        // Given
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("", AskPrompt.choices(request()));

        // Then：直接回车对应最常见的意图，不该一上来就把用户推进编辑态
        assertEquals("a", picker.selected().getValue());
        picker.moveDown();
        assertEquals("b", picker.selected().getValue());
        picker.moveDown();
        assertTrue(AskPrompt.isCustom(picker.selected()));
    }

    @Test
    @DisplayName("只有「其它」那一项算自定义输入")
    void isCustom_should_onlyAcceptOtherValue() {
        assertTrue(AskPrompt.isCustom(new CommandChoice(AskPrompt.OTHER, "其它（自己输入）")));
        assertFalse(AskPrompt.isCustom(new CommandChoice("a", "先做通道")));
        assertFalse(AskPrompt.isCustom(null));
    }

    @Test
    @DisplayName("问题原文优先出现在最前面")
    void render_should_showQuestionFirst() {
        // When
        List<String> body = texts(render(activePicker(), false));

        // Then
        assertTrue(body.get(0).contains("先做通道还是先做 UI？"), body.toString());
    }

    @Test
    @DisplayName("候选区显示文案与说明，并给出键位提示")
    void render_should_showOptionsAndHint() {
        // When
        List<String> body = texts(render(activePicker(), false));

        // Then
        assertTrue(body.stream().anyMatch(line -> line.contains("先做通道")), body.toString());
        assertTrue(body.stream().anyMatch(line -> line.contains("内核与插件先落地")), body.toString());
        assertTrue(body.stream().anyMatch(line -> line.contains("其它")), body.toString());
        assertTrue(body.stream().anyMatch(line -> line.contains("Enter 确认")), body.toString());
    }

    @Test
    @DisplayName("选项态的 Esc 是「放弃作答」，不是「拒绝并中断回合」")
    void render_should_useGiveUpHint() {
        // When
        String body = String.join("\n", texts(render(activePicker(), false)));

        // Then
        assertTrue(body.contains("放弃作答"), body);
        assertFalse(body.contains("拒绝并中断"), body);
    }

    @Test
    @DisplayName("编辑态换成「输入答案」提示：此时按键放行给输入框")
    void render_should_switchHintWhenEditing() {
        // When
        String body = String.join("\n", texts(render(activePicker(), true)));

        // Then
        assertTrue(body.contains("输入你的答案"), body);
        assertTrue(body.contains("Esc 返回选项"), body);
        assertFalse(body.contains("Enter 确认"), body);
    }

    @Test
    @DisplayName("控制字符与控制序列被剔除：问题不能改写屏幕")
    void render_should_stripControlCharacters() {
        // Given：ESC 清屏 + 回车改写 + 双向控制符
        AskRequest request = AskRequest.of("ask_user", "s1", "问题\u001b[2J正文\rmore\u202eend", options());

        // When
        String body = String.join("", texts(AskPrompt.render(pending(request), activePicker(), WIDTH, false)));

        // Then
        assertFalse(body.contains("\u001b"), "ESC 必须被剔除");
        assertFalse(body.contains("\r"), "回车必须被剔除");
        assertFalse(body.contains("\u202e"), "双向控制符必须被剔除");
    }

    @Test
    @DisplayName("超长问题折行显示，到顶后明确写出省略了几行")
    void render_should_wrapAndLimitLongQuestion() {
        // Given：一段远超 6 行的内容
        StringBuilder question = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            question.append("第").append(i).append('问');
        }
        AskRequest request = AskRequest.of("ask_user", "s1", question.toString(), options());

        // When
        List<String> body = texts(AskPrompt.render(pending(request), activePicker(), WIDTH, false));

        // Then
        assertTrue(body.stream().anyMatch(line -> line.contains("已省略")), body.toString());
        for (String line : body) {
            assertTrue(DisplayWidth.of(line) <= WIDTH, "宽度超限：" + line);
        }
    }

    @Test
    @DisplayName("选择页未激活时不渲染选项区，只留问题")
    void render_should_skipOptions_whenPickerInactive() {
        // When
        List<String> body = texts(AskPrompt.render(pending(request()), new CommandChoicePicker(), WIDTH, false));

        // Then：候选区整块不渲染（问题里恰好也有「先做通道」几个字，因此用只在候选区出现的说明来断言）
        assertFalse(body.stream().anyMatch(line -> line.contains("内核与插件先落地")), body.toString());
        assertFalse(body.stream().anyMatch(line -> line.contains("Enter 确认")), body.toString());
        assertTrue(body.get(0).contains("先做通道还是先做 UI？"), body.toString());
    }

    @Test
    @DisplayName("提问为空时返回空列表，调用方据此不显示面板")
    void render_should_returnEmpty_when_pendingNull() {
        assertTrue(AskPrompt.render(null, activePicker(), WIDTH, false).isEmpty());
    }

    /**
     * 渲染一帧。
     *
     * @param picker  选项状态
     * @param editing 是否处于编辑态
     * @return 视觉行
     */
    private static List<VisualLine> render(CommandChoicePicker picker, boolean editing) {
        return AskPrompt.render(pending(request()), picker, WIDTH, editing);
    }

    /**
     * 构造一个已打开的选项状态。
     *
     * @return 选项状态
     */
    private static CommandChoicePicker activePicker() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("", AskPrompt.choices(request()));
        return picker;
    }

    /**
     * 构造一条待答提问。
     *
     * @return 待答提问
     */
    private static AskChannel.Pending pending(AskRequest request) {
        return new AskChannel.Pending(request);
    }

    /**
     * 构造标准提问请求。
     *
     * @return 提问请求
     */
    private static AskRequest request() {
        return AskRequest.of("ask_user", "s1", "先做通道还是先做 UI？", options());
    }

    /**
     * 构造两个候选项（第一项带说明）。
     *
     * @return 候选项
     */
    private static List<AskOption> options() {
        return Arrays.asList(
                AskOption.of("a", "先做通道", "内核与插件先落地"),
                AskOption.of("b", "先做 UI", null));
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
