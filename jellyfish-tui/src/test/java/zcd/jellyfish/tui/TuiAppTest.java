package zcd.jellyfish.tui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.session.SessionMessage;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TuiApp} 外壳开关的单元测试。
 * <p>
 * 只断言不依赖终端的纯逻辑：鼠标捕获逃生门（开了才有滚轮，代价是终端本地选中需要按住修饰键），
 * 以及「启动提示只在全新空会话贴出」的判据（否则 {@code /resume} 打开历史时会被用法说明糊住底部）。
 *
 * @author zcd
 */
@DisplayName("TuiApp 外壳开关")
class TuiAppTest {

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(TuiApp.MOUSE_CAPTURE_PROPERTY);
        System.clearProperty(TuiApp.PLUGIN_PANELS_PROPERTY);
    }

    @Test
    @DisplayName("未设置逃生门时默认启用插件 UI：插件能往界面上放东西是默认能力")
    void pluginPanelsEnabled_should_defaultToTrue() {
        assertTrue(TuiApp.pluginPanelsEnabled());
    }

    @Test
    @DisplayName("显式置为 false 时整体关闭插件 UI（片段与面板都不显示，也不再向插件收集）")
    void pluginPanelsEnabled_should_beFalse_when_propertyFalse() {
        System.setProperty(TuiApp.PLUGIN_PANELS_PROPERTY, "false");

        assertFalse(TuiApp.pluginPanelsEnabled());
    }

    @Test
    @DisplayName("插件 UI 逃生门取值也是大小写不敏感，且取值不是 false 时保持开启")
    void pluginPanelsEnabled_should_ignoreCaseAndStayTrueOtherwise() {
        System.setProperty(TuiApp.PLUGIN_PANELS_PROPERTY, "FALSE");
        assertFalse(TuiApp.pluginPanelsEnabled());

        System.setProperty(TuiApp.PLUGIN_PANELS_PROPERTY, "true");
        assertTrue(TuiApp.pluginPanelsEnabled());
    }

    @Test
    @DisplayName("未设置逃生门时默认开启鼠标捕获，滚轮才可用")
    void mouseCaptureEnabled_should_defaultToTrue() {
        assertTrue(TuiApp.mouseCaptureEnabled());
    }

    @Test
    @DisplayName("显式置为 false 时退回不捕获鼠标的旧行为")
    void mouseCaptureEnabled_should_beFalse_when_propertyFalse() {
        System.setProperty(TuiApp.MOUSE_CAPTURE_PROPERTY, "false");

        assertFalse(TuiApp.mouseCaptureEnabled());
    }

    @Test
    @DisplayName("逃生门取值大小写不敏感")
    void mouseCaptureEnabled_should_ignoreCase() {
        System.setProperty(TuiApp.MOUSE_CAPTURE_PROPERTY, "FALSE");

        assertFalse(TuiApp.mouseCaptureEnabled());
    }

    @Test
    @DisplayName("取值不是 false 时保持开启：逃生门是例外而非常态")
    void mouseCaptureEnabled_should_stayTrue_when_propertyIsNotFalse() {
        System.setProperty(TuiApp.MOUSE_CAPTURE_PROPERTY, "true");

        assertTrue(TuiApp.mouseCaptureEnabled());
    }

    @Test
    @DisplayName("没有历史消息的会话才算全新会话，才贴启动提示")
    void isBrandNewSession_should_beTrue_when_sessionHasNoMessages() {
        assertTrue(TuiApp.isBrandNewSession(Collections.<SessionMessage>emptyList()));
    }

    @Test
    @DisplayName("恢复的历史会话不是全新会话，不贴启动提示")
    void isBrandNewSession_should_beFalse_when_sessionHasMessages() {
        assertFalse(TuiApp.isBrandNewSession(
                Collections.singletonList(SessionMessage.of(LlmMessage.user("hi")))));
    }

    @Test
    @DisplayName("没有当前会话时不算全新会话，避免把提示贴到空页面上")
    void isBrandNewSession_should_beFalse_when_messagesIsNull() {
        assertFalse(TuiApp.isBrandNewSession(null));
    }

    @Test
    @DisplayName("当前上下文取最近一次调用返回的输入 token，而不是会话累计总量")
    void contextTokensOf_should_returnLatestCallPromptTokens() {
        List<SessionMessage> messages = Arrays.asList(
                SessionMessage.of(LlmMessage.user("hi"), new LlmUsage(100, 10, 110)),
                SessionMessage.of(LlmMessage.tool("id", "read_file", "ok"), null),
                SessionMessage.of(LlmMessage.assistant("done"), new LlmUsage(2_000, 50, 2_050)));

        assertEquals(2_000L, TuiApp.contextTokensOf(messages));
    }

    @Test
    @DisplayName("还没有任何带用量的调用时上下文长度为 0")
    void contextTokensOf_should_returnZero_when_noUsageYet() {
        assertEquals(0L, TuiApp.contextTokensOf(Collections.<SessionMessage>emptyList()));
        assertEquals(0L, TuiApp.contextTokensOf(null));
        assertEquals(0L, TuiApp.contextTokensOf(
                Collections.singletonList(SessionMessage.of(LlmMessage.user("hi")))));
    }
}
