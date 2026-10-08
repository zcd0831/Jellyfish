package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.registry.TypeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ConfigWarningReporter} 的单元测试。
 * <p>
 * 它做的两件事各有一面要守：<b>订阅是否真的建立与解除</b>（建不上就没人看得见，解不掉就会在关停后
 * 继续打日志），以及<b>渲染文本</b>（配置里的字段名与路径来自用户的 JSON，带控制字符的键名能清屏、
 * 能在日志里伪造出一整行）。前者直接问注册表（谁在这个事件类型下登记了、通道派发时命不命中），
 * 是同步且确定的；后者直接对渲染结果断言，不必去捞日志。
 *
 * @author zcd
 */
@DisplayName("ConfigWarningReporter 配置告警上报")
class ConfigWarningReporterTest {

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 真实事件通道（本用例只用它验「派发会命中」，不真发事件）。 */
    private final EventChannel channel = new EventChannel(EventChannelOptions.defaults(), registry);

    /** 被测上报器。 */
    private final ConfigWarningReporter reporter = new ConfigWarningReporter(channel);

    @AfterEach
    void tearDown() {
        reporter.close();
        channel.close();
    }

    @Test
    @DisplayName("启动后配置告警就有了订阅者（不再算「无订阅者命中」）")
    void start_should_subscribeConfigWarnings() {
        // Given：启动前这个事件类型下没有任何登记
        assertEquals(0, registry.registrationsOf(ConfigWarningEvent.class).size());

        // When
        reporter.start();

        // Then：登记在册，来源可辨认（关停时按 owner 回收要靠它）
        assertEquals(1, registry.registrationsOf(ConfigWarningEvent.class).size());
        assertEquals("config-warnings", registry.registrationsOf(ConfigWarningEvent.class).get(0).getOwner());
        assertEquals(1, registry.resolve(ConfigWarningEvent.class, null).size(), "通道派发时应当命中这一条");
    }

    @Test
    @DisplayName("关闭之后解除订阅：关停之后再来的告警不该还去打日志")
    void close_should_unsubscribe_when_closed() {
        // Given
        reporter.start();
        assertEquals(1, registry.registrationsOf(ConfigWarningEvent.class).size());

        // When
        reporter.close();

        // Then
        assertEquals(0, registry.registrationsOf(ConfigWarningEvent.class).size());
    }

    @Test
    @DisplayName("启动与关闭都幂等（重复调用不抛、不留第二份订阅）")
    void start_and_close_should_beIdempotent() {
        // When：重复启动不会留下第二份登记；重复关闭不会把别人那一份也拆掉
        reporter.start();
        reporter.start();
        assertEquals(1, registry.registrationsOf(ConfigWarningEvent.class).size());
        reporter.close();
        reporter.close();

        // Then
        assertEquals(0, registry.registrationsOf(ConfigWarningEvent.class).size());
    }

    @Test
    @DisplayName("渲染带上来源，便于用户直接找到是哪个文件")
    void text_should_includeSource_whenSourcePresent() {
        // When / Then
        assertEquals("配置告警（~/.jellyfish/models.json）：配置里有内核不认识的字段：defaultModle",
                ConfigWarningReporter.text(new ConfigWarningEvent("~/.jellyfish/models.json",
                        "配置里有内核不认识的字段：defaultModle")));
    }

    @Test
    @DisplayName("没有来源时只渲染消息（不伪造一个空括号）")
    void text_should_omitSource_whenSourceMissing() {
        // When / Then
        assertEquals("配置告警：模型配置未包含任何 provider",
                ConfigWarningReporter.text(new ConfigWarningEvent(null, "模型配置未包含任何 provider")));
        assertEquals("配置告警：模型配置未包含任何 provider",
                ConfigWarningReporter.text(new ConfigWarningEvent("  ", "模型配置未包含任何 provider")));
    }

    @Test
    @DisplayName("文本里的控制字符与换行被清掉：字段名来自用户的 JSON，不能让它在终端清屏或伪造日志行")
    void text_should_stripControlChars_whenTheyAppearInSourceOrMessage() {
        // Given：一个带 ESC 序列的键名，与一条带换行的消息（换行能在日志里伪造出一整行「已完成」）
        ConfigWarningEvent event = new ConfigWarningEvent("models.json\u001b[2J",
                "未知字段：b\u001b]0;被改的窗口标题\u0007ad\n已授权");

        // When
        String text = ConfigWarningReporter.text(event);

        // Then：没有 ESC、没有响铃、没有换行
        assertEquals(false, text.contains("\u001b"));
        assertEquals(false, text.contains("\u0007"));
        assertEquals(false, text.contains("\n"));
        assertEquals("配置告警（models.json[2J）：未知字段：b]0;被改的窗口标题ad 已授权", text);
    }

}
