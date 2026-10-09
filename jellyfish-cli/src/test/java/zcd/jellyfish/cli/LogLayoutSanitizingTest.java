package zcd.jellyfish.cli;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志布局的控制字符过滤：日志是「不可信文本 → 终端」的第四条出口。
 * <p>
 * <b>它防的是什么</b>：{@code -cli} / {@code -server} 模式下日志写 stderr，而日志里会拼进模型输出、
 * 工具参数、子进程的原始字节、文件路径与异常消息。一个 {@code ESC} 可以清屏、一个 OSC 序列可以改窗口标题
 * ——这两件在上一轮的安全审查里都被实测复现过（撤掉过滤后，连断言失败的打印都把屏幕清了）。
 * <p>
 * <b>为什么测的是配置文件而不是某个类</b>：过滤挂在 log4j2 的布局上（出口只有一处，而拼进外部文本的
 * 调用点有几十处），因此「防线」就是那两行 pattern。本类把两份配置里的 pattern 原样取出来、
 * 用真正的 {@link PatternLayout} 渲染一条带敌意内容的日志事件，再断言输出里不剩控制字符——
 * 这样既验证了正则本身，也验证了「配置文件里那份」没被改动（改坏了这里就红）。
 * <p>
 * 断言同时钉住「换行与制表必须留着」：异常栈压成一行会让日志不可读，那不是安全，那是另一种损坏。
 *
 * @author zcd
 */
@DisplayName("日志布局的控制字符过滤")
class LogLayoutSanitizingTest {

    /** ESC：清屏、改标题那些序列的起始字节。 */
    private static final char ESC = '\u001B';

    /** BEL：OSC 序列的终结符（改窗口标题靠它收尾）。 */
    private static final char BEL = '\u0007';

    /** 两份配置：stderr（`-cli`/`-server`）与 TUI 的日志文件。 */
    private static final String[] CONFIG_FILES = {"log4j2.xml", "log4j2-tui.xml"};

    @Test
    @DisplayName("两份配置里的日志布局都必须剥掉控制字符")
    void everyLayout_should_stripControlCharacters() throws IOException {
        for (String config : CONFIG_FILES) {
            String pattern = patternOf(config);

            // 敌意内容：清屏 + 改窗口标题 + 回车 + 一个字面换行
            String hostile = "答案" + ESC + "[2J" + ESC + "]0;被改掉的标题" + BEL + "\r换行->\n后一行";

            // When
            String rendered = render(pattern, hostile);

            // Then：控制字符一个不剩，而换行留着（栈与多行消息要能读）
            assertFalse(rendered.contains(String.valueOf(ESC)), config + " 里漏了 ESC: " + rendered);
            assertFalse(rendered.contains(String.valueOf(BEL)), config + " 里漏了 BEL: " + rendered);
            assertFalse(rendered.contains("\r"), config + " 里漏了回车: " + rendered);
            assertTrue(rendered.contains("答案[2J]0;被改掉的标题换行->"), config + " 的正文被改坏: " + rendered);
            assertTrue(rendered.contains("答案"), config + " 丢了正文");
            assertTrue(rendered.indexOf('\n') >= 0, config + " 把换行也剥掉了");
            // 换行之后的内容仍在（不是「第一个换行就截断」）
            assertTrue(rendered.contains("后一行"), config + " 丢了换行之后的内容");
        }
    }

    @Test
    @DisplayName("异常消息与栈也必须走同一条过滤")
    void throwable_should_stripControlCharacters() throws IOException {
        // Given：对面可控的文本进了异常消息（例如解析失败时回显原文）
        IllegalStateException failure = new IllegalStateException("坏输入：" + ESC + "[2J 与 OSC" + ESC + "]0;x" + BEL);

        // When
        String rendered = render(patternOf("log4j2.xml"), "调用失败", failure);

        // Then
        assertFalse(rendered.contains(String.valueOf(ESC)), rendered);
        assertFalse(rendered.contains(String.valueOf(BEL)), rendered);
        // 栈本身仍在：过滤不该把异常变成一句话
        assertTrue(rendered.contains("IllegalStateException"), rendered);
        assertTrue(rendered.contains("坏输入：[2J 与 OSC]0;x"), rendered);
    }

    /**
     * 取出配置里的 pattern 属性（XML 里的 {@code &amp;&amp;} 还原成 {@code &&}）。
     *
     * @param config 类路径上的配置文件
     * @return 布局 pattern
     * @throws IOException 读取失败时抛出
     */
    private static String patternOf(String config) throws IOException {
        String xml = read(config);
        Matcher matcher = Pattern.compile("pattern=\"([^\"]+)\"").matcher(xml);
        assertTrue(matcher.find(), config + " 里找不到 pattern");
        return matcher.group(1).replace("&amp;", "&");
    }

    /**
     * 用给定 pattern 渲染一条日志事件。
     *
     * @param pattern 布局 pattern
     * @param message 消息正文
     * @return 渲染结果
     */
    private static String render(String pattern, String message) {
        return render(pattern, message, null);
    }

    /**
     * 用给定 pattern 渲染一条带异常的日志事件。
     *
     * @param pattern 布局 pattern
     * @param message 消息正文
     * @param thrown  异常，可为 {@code null}
     * @return 渲染结果
     */
    private static String render(String pattern, String message, Throwable thrown) {
        PatternLayout layout = PatternLayout.newBuilder()
                .withPattern(pattern)
                // 与配置文件同一口径：框架不再自动补一份未过滤的异常
                .withAlwaysWriteExceptions(false)
                .withConfiguration(new DefaultConfiguration())
                .build();
        LogEvent event = Log4jLogEvent.newBuilder()
                .setLoggerName("zcd.jellyfish.cli.LogLayoutSanitizingTest")
                .setLevel(thrown == null ? Level.WARN : Level.ERROR)
                .setMessage(new SimpleMessage(message))
                .setThrown(thrown)
                .setTimeMillis(System.currentTimeMillis())
                .build();
        return layout.toSerializable(event);
    }

    /**
     * 读类路径上的资源。
     *
     * @param name 资源名
     * @return 文件内容
     * @throws IOException 读不到时抛出
     */
    private static String read(String name) throws IOException {
        try (InputStream stream = LogLayoutSanitizingTest.class.getClassLoader().getResourceAsStream(name)) {
            assertTrue(stream != null, "类路径上没有 " + name);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            assertEquals(true, text.contains("<PatternLayout"), name + " 里没有 PatternLayout");
            return text;
        }
    }
}
