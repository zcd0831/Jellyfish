package zcd.jellyfish.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.event.EventResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ToolRenderHint;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.tui.text.DisplayWidth;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端渲染冒烟：把「会话消息」一路画进 {@link Buffer}，断言屏幕上真的出现了什么。
 * <p>
 * <b>为什么需要这一层</b>：其余单测都在「投影结果」这一层断言，而「投影结果画到屏幕上」还隔着
 * {@code richText → TamboUI 布局 → 缓冲区}。汉字宽度、样式段拼接、以及「我们算出来的行数
 * 与框架真正画出来的行数是否一致」都只在这一层才暴露——而它们错了的症状正是滚动错位。
 * <p>
 * <b>不需要终端</b>：{@code Frame.forTesting(buffer)} 是框架自带的无终端渲染入口，
 * 因此这条冒烟能在 CI 里跑，不必依赖 PTY。
 *
 * @author zcd
 */
@DisplayName("端到端渲染冒烟")
class RenderSmokeTest {

    /** 终端列数。 */
    private static final int TERMINAL_WIDTH = 80;

    /** 终端行数。 */
    private static final int TERMINAL_HEIGHT = 24;

    /**
     * 声明当前线程为渲染线程。
     * <p>
     * TamboUI 有一道渲染线程守卫（{@code RenderThread.checkRenderThread}），会拒绝在别的线程上渲染，
     * 而它只开放了包内可见的 {@code markAsRenderThread()}——框架没给外部留测试口径。
     * 单测里我们本来就在同一个线程上串行渲染，所以这里做的是「如实声明」而不是「绕过检查」；
     * 若框架改动导致这条调用失效，测试应当直接失败，因此这里不做静默兜底。
     *
     * @throws Exception 反射调用失败时抛出
     */
    @BeforeAll
    static void markRenderThread() throws Exception {
        Method mark = Class.forName("dev.tamboui.tui.RenderThread")
                .getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
    }

    @Test
    @DisplayName("markdown 正文真的出现在屏幕上：标题、列表、代码块各就各位")
    void render_should_paintMarkdownBody() {
        // Given：一条含标题、列表与代码块的助手消息
        String answer = "## 小节\n\n"
                + "结论是**加粗**的。\n\n"
                + "- 第一项\n"
                + "- 第二项\n\n"
                + "```java\nint a = 1;\n```\n";

        // When
        List<String> screen = paint(sessionOf(answer));

        // Then：每一块都出现在屏幕上，markdown 标记本身不再出现
        assertTrue(screen.stream().anyMatch(line -> line.contains("\u258c 小节")), screen.toString());
        assertTrue(screen.stream().anyMatch(line -> line.contains("结论是加粗的。")), screen.toString());
        assertTrue(screen.stream().anyMatch(line -> line.contains("\u2022 第一项")), screen.toString());
        assertTrue(screen.stream().anyMatch(line -> line.contains("```java")), screen.toString());
        assertTrue(screen.stream().anyMatch(line -> line.contains("int a = 1;")), screen.toString());
        assertFalse(screen.stream().anyMatch(line -> line.contains("**")), screen.toString());
        assertFalse(screen.stream().anyMatch(line -> line.contains("##")), screen.toString());
    }

    @Test
    @DisplayName("屏幕上每一行都不超过终端宽度：越界会触发终端自行折行，滚动随之错位")
    void render_should_neverOverflowTerminalWidth() {
        // Given：含长中文段落与超长代码行的回答
        String answer = "一段很长的中文说明，用来验证按列数换行是否真的生效，"
                + "中英混排 mixed with ASCII 也不应该越界。\n\n"
                + "```java\nString s = \"这一行代码特别长，长到一定超过终端可用宽度，用来验证截断\";\n```\n";

        // When
        for (int width : new int[]{60, 80, 120}) {
            List<String> screen = paint(sessionOf(answer), width);

            // Then
            for (String line : screen) {
                assertTrue(DisplayWidth.of(line) <= width,
                        "宽 " + width + " 下越界（" + DisplayWidth.of(line) + " 列）：" + line);
            }
        }
    }

    @Test
    @DisplayName("助手正文画在消息区内部：边框与缩进都没有被撑破")
    void render_should_keepBodyInsideMessagePanel() {
        // Given
        String answer = "## 标题\n\n正文内容。\n";

        // When
        List<String> screen = paint(sessionOf(answer));

        // Then：消息区有圆角边框，markdown 内容在边框之内
        assertTrue(screen.get(0).startsWith("\u256d"), "首行应是消息区上边框：" + screen.get(0));
        assertTrue(screen.stream().anyMatch(line -> line.startsWith("\u2502")), screen.toString());
        assertTrue(screen.stream().anyMatch(line -> line.contains("\u258c 标题")), screen.toString());
    }

    @Test
    @DisplayName("工具轨迹与用户消息照旧：它们不参与 markdown 渲染")
    void render_should_keepTraceAndUserMessageAsPlainText() {
        // Given
        List<SessionMessage> messages = new ArrayList<SessionMessage>();
        messages.add(SessionMessage.of(LlmMessage.user("## 这是用户打的字")));
        messages.add(SessionMessage.of(LlmMessage.assistant("**看**一下", Collections.emptyList())));
        messages.add(SessionMessage.of(LlmMessage.tool("c1", "read_file", "内容")));

        // When
        List<String> screen = paint(messages);

        // Then：用户消息原样出现，助手正文去掉标记，工具轨迹只有名字
        assertTrue(screen.stream().anyMatch(line -> line.contains("## 这是用户打的字")), screen.toString());
        assertTrue(screen.stream().anyMatch(line -> line.contains("\u276f ## 这是用户打的字")), screen.toString());
        assertTrue(screen.stream().anyMatch(line -> line.contains("看一下")), screen.toString());
        assertTrue(screen.stream().anyMatch(line -> line.contains("\u23bf read_file")), screen.toString());
    }

    @Test
    @DisplayName("行数账本与渲染结果一致：跟随底部时最后一行内容真的在屏幕上")
    void render_should_alignRowAccountingWithBuffer() {
        // Given：内容多到超出消息区高度
        StringBuilder answer = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            answer.append("- 第 ").append(i).append(" 项\n");
        }

        ChatState state = new ChatState();
        List<SessionMessage> messages = sessionOf(answer.toString());
        ChatLayout layout = layout();
        ChatState.View view = state.view("s-1", messages, layout.getMessageWidth(),
                layout.getMessageRows(), TranscriptProjector.DEFAULT_MAX_MESSAGES,
                Collections.<String, ToolRenderHint>emptyMap());

        // Then：投影行数比消息区高，因此窗口被切成尾部片段
        assertTrue(state.getTotalRows() > layout.getMessageRows(),
                "总行数 " + state.getTotalRows() + " 应超过消息区 " + layout.getMessageRows());
        assertEquals(layout.getMessageRows(), view.getLines().size());

        // When：把这一帧画出来
        List<String> screen = paint(view, layout, "s-1", messages);

        // Then：屏幕上出现的是最后一项（跟随底部），而不是第一项
        assertTrue(screen.stream().anyMatch(line -> line.contains("第 40 项")), screen.toString());
        assertFalse(screen.stream().anyMatch(line -> line.contains("第 1 项")), screen.toString());
    }

    @Test
    @DisplayName("整屏渲染后硬件光标落在输入框内：留在最后一格会让 IME 预编辑串从末列换行、把整屏顶走")
    void render_should_placeHardwareCursorInsideInputBox() {
        // Given：一帧带会话内容的完整版式
        ChatLayout layout = layout();
        ChatState state = new ChatState();
        List<SessionMessage> messages = sessionOf("你好");
        ChatState.View view = state.view("s-1", messages, layout.getMessageWidth(),
                layout.getMessageRows(), TranscriptProjector.DEFAULT_MAX_MESSAGES,
                Collections.<String, ToolRenderHint>emptyMap());
        Buffer buffer = Buffer.empty(Rect.of(TERMINAL_WIDTH, TERMINAL_HEIGHT));
        Frame frame = Frame.forTesting(buffer);

        // When：把整帧画进缓冲区
        new ChatShell(new ChatInputView(keys -> EventResult.HANDLED))
                .render(view, "会话", "状态栏", Overlay.none(), Collections.emptyMap(), layout)
                .render(frame, frame.area(), RenderContext.empty());

        // Then：硬件光标在输入区那一行，而不是终端右下角的状态栏末列
        Position cursor = frame.cursorPosition().orElse(null);
        assertNotNull(cursor, "整屏渲染后必须有硬件光标位置，否则 IME 预编辑串无处可画");
        assertTrue(cursor.y() >= layout.getMessageRows(), "光标应在消息区下方的输入区：" + cursor);
        assertTrue(cursor.y() < TERMINAL_HEIGHT - 1, "光标不得落在状态栏那一行：" + cursor);
    }

    /**
     * 把一份会话画成一屏，并按行取出屏幕文本。
     *
     * @param messages 会话消息
     * @return 屏幕每一行的文本（去掉行尾空白）
     */
    private static List<String> paint(List<SessionMessage> messages) {
        return paint(messages, TERMINAL_WIDTH);
    }

    /**
     * 按指定终端宽度把一份会话画成一屏。
     *
     * @param messages 会话消息
     * @param width    终端列数
     * @return 屏幕每一行的文本
     */
    private static List<String> paint(List<SessionMessage> messages, int width) {
        ChatLayout layout = ChatLayout.compute(width, TERMINAL_HEIGHT, inputRows(),
                ChatShell.overlayRows(null), Collections.emptyMap());
        ChatState state = new ChatState();
        ChatState.View view = state.view("s-1", messages, layout.getMessageWidth(),
                layout.getMessageRows(), TranscriptProjector.DEFAULT_MAX_MESSAGES,
                Collections.<String, ToolRenderHint>emptyMap());
        return paint(view, layout, "s-1", messages, width);
    }

    /**
     * 把给定的一帧画进缓冲区。
     *
     * @param view     消息区窗口
     * @param layout   版式账本
     * @param sessionId 会话标识
     * @param messages 会话消息
     * @param width    终端列数
     * @return 屏幕每一行的文本
     */
    private static List<String> paint(ChatState.View view, ChatLayout layout, String sessionId,
                                      List<SessionMessage> messages, int width) {
        Buffer buffer = Buffer.empty(Rect.of(width, TERMINAL_HEIGHT));
        Frame frame = Frame.forTesting(buffer);
        ChatShell shell = new ChatShell(new ChatInputView(keys -> EventResult.HANDLED));
        shell.render(view, "会话", "状态栏", Overlay.none(), Collections.emptyMap(), layout)
                .render(frame, frame.area(), RenderContext.empty());
        return lines(buffer);
    }

    /**
     * 默认终端宽度下画一帧。
     *
     * @param view     消息区窗口
     * @param layout   版式账本
     * @param sessionId 会话标识
     * @param messages 会话消息
     * @return 屏幕每一行的文本
     */
    private static List<String> paint(ChatState.View view, ChatLayout layout, String sessionId,
                                      List<SessionMessage> messages) {
        return paint(view, layout, sessionId, messages, TERMINAL_WIDTH);
    }

    /**
     * 构造默认版式。
     *
     * @return 版式账本
     */
    private static ChatLayout layout() {
        return ChatLayout.compute(TERMINAL_WIDTH, TERMINAL_HEIGHT, inputRows(),
                ChatShell.overlayRows(null), Collections.emptyMap());
    }

    /**
     * 取输入区占用的行数。
     *
     * @return 行数
     */
    private static int inputRows() {
        return new ChatInputView(keys -> EventResult.HANDLED).panelRows();
    }

    /**
     * 构造一条含指定助手正文的会话。
     *
     * @param answer 助手正文
     * @return 会话消息列表
     */
    private static List<SessionMessage> sessionOf(String answer) {
        List<SessionMessage> messages = new ArrayList<SessionMessage>();
        messages.add(SessionMessage.of(LlmMessage.user("你好")));
        messages.add(SessionMessage.of(LlmMessage.assistant(answer)));
        return messages;
    }

    /**
     * 从缓冲区读出每一行的文本。
     *
     * @param buffer 缓冲区
     * @return 行文本列表（行尾空白已去掉）
     */
    private static List<String> lines(Buffer buffer) {
        List<String> result = new ArrayList<String>(buffer.height());
        StringBuilder line = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            line.setLength(0);
            for (int x = 0; x < buffer.width(); x++) {
                Cell cell = buffer.get(x, y);
                line.append(cell == null || cell.isContinuation() ? "" : cell.symbol());
            }
            result.add(stripTrailing(line.toString()));
        }
        return result;
    }

    /**
     * 去掉行尾空白。
     *
     * @param line 一行文本
     * @return 去掉行尾空白后的文本
     */
    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        return line.substring(0, end);
    }
}
