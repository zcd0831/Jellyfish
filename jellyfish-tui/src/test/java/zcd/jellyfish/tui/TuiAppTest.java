package zcd.jellyfish.tui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandResult;
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
 * 只断言不依赖终端的纯逻辑：鼠标捕获逃生门（开了才有滚轮，代价是终端本地选中需要按住修饰键）、
 * 首页命令分流（{@code /resume} {@code /delete} 不建会话，其余命令先建会话），
 * 以及 {@code /help} 只给无参形式追加外壳侧用法说明。
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
    @DisplayName("首页命令分流：/new /resume /delete 不预先建会话，其余命令（含 /session /help）先建会话")
    void isSessionDomainCommand_should_onlyMatchResumeAndDelete() {
        assertTrue(TuiApp.isSessionDomainCommand("/new"));
        assertTrue(TuiApp.isSessionDomainCommand("/resume"));
        assertTrue(TuiApp.isSessionDomainCommand("/resume abc"));
        assertTrue(TuiApp.isSessionDomainCommand("/delete abc"));
        assertTrue(TuiApp.isSessionDomainCommand("/rm abc"));

        assertFalse(TuiApp.isSessionDomainCommand("/session"));
        assertFalse(TuiApp.isSessionDomainCommand("/help"));
        assertFalse(TuiApp.isSessionDomainCommand("/model gpt-4"));
        assertFalse(TuiApp.isSessionDomainCommand("你好"));
        // 无前缀的普通对话不得被当成命令，否则首页上会跳过建会话
        assertFalse(TuiApp.isSessionDomainCommand("resume this"));
        assertFalse(TuiApp.isSessionDomainCommand("delete everything"));
        assertFalse(TuiApp.isSessionDomainCommand(null));
    }

    @Test
    @DisplayName("只有无参 /help（含别名）才算「查键位」的那次帮助")
    void isBareHelp_should_matchOnlyBareHelp() {
        assertTrue(TuiApp.isBareHelp("/help"));
        assertTrue(TuiApp.isBareHelp("/h"));
        assertTrue(TuiApp.isBareHelp("/?"));
        assertTrue(TuiApp.isBareHelp("  /help  "));

        assertFalse(TuiApp.isBareHelp("/help /model"));
        assertFalse(TuiApp.isBareHelp("/model"));
        assertFalse(TuiApp.isBareHelp("help"));
    }

    @Test
    @DisplayName("无参 /help 的输出追加 TUI 用法说明，其余命令原样返回")
    void withShellUsage_should_appendUsageOnlyToBareHelp() {
        String help = TuiApp.withShellUsage("/help", CommandResult.ok("命令列表"));

        assertTrue(help.startsWith("命令列表"));
        assertTrue(help.contains(ShellUsage.text()));
        assertEquals("命令列表", TuiApp.withShellUsage("/model", CommandResult.ok("命令列表")));
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
