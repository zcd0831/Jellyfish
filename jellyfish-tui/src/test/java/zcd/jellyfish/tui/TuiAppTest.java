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
    @DisplayName("以逃生门启动后又运行期收回鼠标：退出前必须自己关掉上报，否则终端会带着鼠标模式回到 shell")
    void mouseCaptureNeedsRestore_should_beTrue_when_capturedButConfiguredOff() {
        assertTrue(TuiApp.mouseCaptureNeedsRestore(true, false));
    }

    @Test
    @DisplayName("其余三种组合都不需要自己动手：框架按启动配置关一次即可")
    void mouseCaptureNeedsRestore_should_beFalse_otherwise() {
        assertFalse(TuiApp.mouseCaptureNeedsRestore(true, true));
        assertFalse(TuiApp.mouseCaptureNeedsRestore(false, true));
        assertFalse(TuiApp.mouseCaptureNeedsRestore(false, false));
    }

    @Test
    @DisplayName("回合进行中，外壳自有命令照常执行：与 Ctrl+T / Ctrl+E / Ctrl+O 是同一个动作，不能有两条语义")
    void routeOf_should_run_shell_command_while_turn_running() {
        // 五条命令各取一条代表（/ui 带参数那一条正是两级页面的级联形式）
        assertTrue(TuiApp.routeOf("/exit", true) == TuiApp.SubmitRoute.SHELL_COMMAND);
        assertTrue(TuiApp.routeOf("/quit", true) == TuiApp.SubmitRoute.SHELL_COMMAND);
        assertTrue(TuiApp.routeOf("/thinking", true) == TuiApp.SubmitRoute.SHELL_COMMAND);
        assertTrue(TuiApp.routeOf("/toolargs", true) == TuiApp.SubmitRoute.SHELL_COMMAND);
        assertTrue(TuiApp.routeOf("/mouse on", true) == TuiApp.SubmitRoute.SHELL_COMMAND);
        assertTrue(TuiApp.routeOf("/ui dock", true) == TuiApp.SubmitRoute.SHELL_COMMAND);
        // 前后空白不参与判定（与 takeText() 的 trim 同一口径）
        assertTrue(TuiApp.routeOf("  /exit  ", true) == TuiApp.SubmitRoute.SHELL_COMMAND);
    }

    @Test
    @DisplayName("回合进行中的普通文本仍被拒收：草稿留在输入框，等回合结束再发")
    void routeOf_should_reject_plain_text_while_turn_running() {
        assertTrue(TuiApp.routeOf("你好", true) == TuiApp.SubmitRoute.REJECTED);
        // 内核命令域的命令不算外壳自有命令：它们要写会话（起回合 / 改会话），回合中仍该拦下
        assertTrue(TuiApp.routeOf("/help", true) == TuiApp.SubmitRoute.REJECTED);
        assertTrue(TuiApp.routeOf("/new", true) == TuiApp.SubmitRoute.REJECTED);
    }

    @Test
    @DisplayName("空闲时普通文本进提交管线，外壳自有命令同样先被外壳截胡")
    void routeOf_should_submit_plain_text_when_idle() {
        assertTrue(TuiApp.routeOf("你好", false) == TuiApp.SubmitRoute.SUBMIT);
        assertTrue(TuiApp.routeOf("/help", false) == TuiApp.SubmitRoute.SUBMIT);
        assertTrue(TuiApp.routeOf("/exit", false) == TuiApp.SubmitRoute.SHELL_COMMAND);
    }

    @Test
    @DisplayName("空白输入什么都不做，且不因回合进行中而变成一条提示")
    void routeOf_should_ignore_blank_input() {
        assertTrue(TuiApp.routeOf(null, false) == TuiApp.SubmitRoute.IGNORED);
        assertTrue(TuiApp.routeOf("", true) == TuiApp.SubmitRoute.IGNORED);
        assertTrue(TuiApp.routeOf("   \n  ", true) == TuiApp.SubmitRoute.IGNORED);
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
