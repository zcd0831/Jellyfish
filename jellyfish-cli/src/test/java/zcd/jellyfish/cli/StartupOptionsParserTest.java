package zcd.jellyfish.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.server.ServerConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StartupOptionsParser} 的单元测试：验证三种模式旗标、裸跑兜底、取值选项、开关、非法组合与帮助优先级。
 *
 * @author zcd
 */
class StartupOptionsParserTest {

    @ParameterizedTest
    @CsvSource({
            "-cli, CLI",
            "-tui, TUI",
            "-server, SERVER"
    })
    void parse_should_resolve_mode_when_flag_given(String flag, StartupOptions.Mode expected) {
        StartupOptions options = StartupOptionsParser.parse(new String[] {flag});

        assertEquals(expected, options.getMode());
    }

    @Test
    void parse_should_fail_when_mode_missing() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-p", "你好"}));

        assertTrue(error.getMessage().contains("请指定启动模式"));
    }

    @Test
    void parse_should_fail_when_mode_missing_even_for_switch_only_given() {
        // 兜底只认「零参数」，带一个通用开关同样要求显式给模式：
        // 边界写死在参数个数上，就不会因为多认出一个开关而悄悄放宽。
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"--verbose"}));

        assertTrue(error.getMessage().contains("请指定启动模式"));
    }

    @Test
    void parse_should_default_to_tui_when_no_args_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[0]);

        assertEquals(StartupOptions.Mode.TUI, options.getMode());
        assertNull(options.getPrompt());
        assertNull(options.getSessionId());
    }

    @Test
    void parse_should_fail_when_mode_given_twice() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-cli", "-tui"}));

        assertTrue(error.getMessage().contains("只能指定一次"));
    }

    @Test
    void parse_should_keep_prompt_when_print_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli", "-p", "今天天气怎么样？"});

        assertEquals("今天天气怎么样？", options.getPrompt());
    }

    @Test
    void parse_should_keep_prompt_when_long_flag_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli", "--print", "hello"});

        assertEquals("hello", options.getPrompt());
    }

    @Test
    void parse_should_leave_prompt_null_when_absent_so_stdin_is_used() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli"});

        assertNull(options.getPrompt());
    }

    @Test
    void parse_should_accept_empty_prompt_and_let_caller_decide() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli", "-p", ""});

        assertEquals("", options.getPrompt());
    }

    @Test
    void parse_should_fail_when_prompt_value_missing() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-cli", "-p"}));

        assertTrue(error.getMessage().contains("缺少取值"));
    }

    @Test
    void parse_should_use_positional_port_when_server_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-server", "9096"});

        assertEquals(StartupOptions.Mode.SERVER, options.getMode());
        assertEquals(9096, options.getPort());
    }

    @Test
    void parse_should_use_default_port_when_server_without_port() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-server"});

        assertEquals(StartupOptions.DEFAULT_PORT, options.getPort());
        assertEquals(StartupOptions.DEFAULT_HOST, options.getHost());
    }

    @Test
    void parse_should_accept_port_option_when_consistent_with_positional() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-server", "9096", "--port", "9096"});

        assertEquals(9096, options.getPort());
    }

    @Test
    void parse_should_fail_when_port_option_conflicts_with_positional() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-server", "9096", "--port", "9097"}));

        assertTrue(error.getMessage().contains("端口重复指定且不一致"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "65536"})
    void parse_should_fail_when_port_invalid(String port) {
        assertThrows(JellyfishException.class, () -> StartupOptionsParser.parse(new String[] {"-server", port}));
    }

    @Test
    void parse_should_fail_when_positional_given_to_cli() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-cli", "9096"}));

        assertTrue(error.getMessage().contains("只有 -server 支持端口、绑定地址与 --api-key"));
    }

    @Test
    void parse_should_fail_when_host_given_to_tui() {
        assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-tui", "--host", "0.0.0.0"}));
    }

    @Test
    void parse_should_keep_api_key_when_server_given_api_key() {
        StartupOptions options = StartupOptionsParser.parse(
                new String[] {"-server", "--api-key", "s3cret"});

        assertEquals("s3cret", options.getApiKey());
    }

    @Test
    void parse_should_leave_api_key_null_when_not_given() {
        assertNull(StartupOptionsParser.parse(new String[] {"-server"}).getApiKey());
    }

    @Test
    void parse_should_fail_when_api_key_given_to_cli() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-cli", "--api-key", "s3cret"}));

        assertTrue(error.getMessage().contains("只有 -server 支持端口、绑定地址与 --api-key"));
    }

    @Test
    void parse_should_fail_when_api_key_blank() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-server", "--api-key", "   "}));

        assertTrue(error.getMessage().contains("不能为空白"));
    }

    @Test
    void parse_should_mention_environment_variable_in_usage() {
        // 帮助里写的变量名必须就是实际读的那个：两处各写一遍字面量迟早漂移
        assertTrue(StartupOptionsParser.usage().contains(ServerConfig.ENV_API_KEY),
                StartupOptionsParser.usage());
    }

    @Test
    void parse_should_fail_when_session_given_to_server() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-server", "--session", "abc"}));

        assertTrue(error.getMessage().contains("-server 不支持 --session"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-tui", "-server"})
    void parse_should_fail_when_agent_given_outside_cli(String mode) {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {mode, "--agent", "coder"}));

        assertTrue(error.getMessage().contains("--agent 只在 -cli 下被接受"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-tui", "-server"})
    void parse_should_fail_when_model_given_outside_cli(String mode) {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {mode, "--model", "openai/gpt-4o"}));

        assertTrue(error.getMessage().contains("--model 只在 -cli 下被接受"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-tui", "-server"})
    void parse_should_fail_when_print_given_outside_cli(String mode) {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {mode, "-p", "hello"}));

        assertTrue(error.getMessage().contains("-p / --print 只在 -cli 下被接受"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-tui", "-server"})
    void parse_should_fail_when_show_thinking_given_outside_cli(String mode) {
        // 与 --show-tool-args 同一条理由：TUI 的能力在运行期开关（Ctrl+T / /thinking）上，
        // 不需要启动参数；Server 根本没有终端界面
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {mode, "--show-thinking"}));

        assertTrue(error.getMessage().contains("--show-thinking 只在 -cli 下被接受"), error.getMessage());
    }

    @Test
    void parse_should_keep_show_thinking_when_cli_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli", "--show-thinking"});

        assertTrue(options.isShowThinking());
        assertEquals(StartupOptions.Mode.CLI, options.getMode());
    }

    @Test
    void parse_should_keep_show_tool_args_when_cli_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli", "--show-tool-args"});

        assertTrue(options.isShowToolArgs());
        assertEquals(StartupOptions.Mode.CLI, options.getMode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-tui", "-server"})
    void parse_should_fail_when_show_tool_args_given_outside_cli(String mode) {
        // TUI 的能力在运行期开关（Ctrl+E / /toolargs）上，不需要启动参数；Server 根本没有终端界面
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {mode, "--show-tool-args"}));

        assertTrue(error.getMessage().contains("--show-tool-args 只在 -cli 下被接受"), error.getMessage());
    }

    @Test
    void parse_should_default_show_tool_args_to_false() {
        assertFalse(StartupOptionsParser.parse(new String[] {"-cli"}).isShowToolArgs());
    }

    @Test
    void usage_should_document_show_tool_args() {
        assertTrue(StartupOptionsParser.usage().contains("--show-tool-args"), StartupOptionsParser.usage());
    }

    @Test
    void parse_should_keep_session_when_tui_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-tui", "--session", "abc"});

        assertEquals("abc", options.getSessionId());
    }

    @Test
    void parse_should_fail_when_more_than_one_positional() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-server", "9096", "9097"}));

        assertTrue(error.getMessage().contains("位置参数过多"));
    }

    @Test
    void parse_should_keep_host_when_server_given_host() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-server", "--host", "0.0.0.0"});

        assertEquals("0.0.0.0", options.getHost());
    }

    @Test
    void parse_should_split_model_into_provider_and_name() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli", "--model", "openai/gpt-4o"});

        assertEquals("openai", options.getProvider());
        assertEquals("gpt-4o", options.getModel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o", "/gpt-4o", "openai/"})
    void parse_should_fail_when_model_not_provider_slash_name(String model) {
        assertThrows(JellyfishException.class, () -> StartupOptionsParser.parse(new String[] {"-cli", "--model", model}));
    }

    @Test
    void parse_should_split_on_first_slash_when_model_name_contains_slash() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli", "--model", "openai/a/b/c"});

        assertEquals("openai", options.getProvider());
        assertEquals("a/b/c", options.getModel());
    }

    @Test
    void parse_should_keep_agent_and_session() {
        StartupOptions options = StartupOptionsParser.parse(
                new String[] {"-cli", "--agent", "coder", "--session", "abc"});

        assertEquals("coder", options.getAgentId());
        assertEquals("abc", options.getSessionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--agent", "--session", "--host"})
    void parse_should_fail_when_non_blank_value_is_blank(String flag) {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-cli", flag, "   "}));

        assertTrue(error.getMessage().contains("不能为空白"));
    }

    @Test
    void parse_should_set_switches_when_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli", "--show-thinking", "--verbose"});

        assertTrue(options.isShowThinking());
        assertTrue(options.isVerbose());
    }

    @Test
    void parse_should_set_trust_project_config_when_given_in_any_mode() {
        // 它是启动期的安全表态，不是模式专属能力：三种外壳都得能带上
        for (String mode : new String[] {"-cli", "-tui", "-server"}) {
            StartupOptions options = StartupOptionsParser.parse(new String[] {mode, "--trust-project-config"});
            assertTrue(options.isTrustProjectConfig(), mode);
        }
    }

    @Test
    void parse_should_default_switches_to_false() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-cli"});

        assertFalse(options.isShowThinking());
        assertFalse(options.isVerbose());
        // 缺省不信任项目级配置：这个方向的缺省必须是「不加载」
        assertFalse(options.isTrustProjectConfig());
        assertFalse(options.isHelp());
        assertFalse(options.isVersion());
        assertNull(options.getAgentId());
        assertNull(options.getSessionId());
        assertNull(options.getProvider());
        assertNull(options.getModel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-h", "--help"})
    void parse_should_set_help_when_given(String flag) {
        assertTrue(StartupOptionsParser.parse(new String[] {flag}).isHelp());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-V", "--version"})
    void parse_should_set_version_when_given(String flag) {
        assertTrue(StartupOptionsParser.parse(new String[] {flag}).isVersion());
    }

    @Test
    void parse_should_not_fail_on_missing_mode_when_help_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-h"});

        assertTrue(options.isHelp());
        assertEquals(StartupOptions.Mode.CLI, options.getMode());
    }

    @Test
    void parse_should_not_fail_on_missing_mode_when_version_given() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-V"});

        assertTrue(options.isVersion());
    }

    @Test
    void parse_should_keep_mode_when_help_given_alongside_mode() {
        StartupOptions options = StartupOptionsParser.parse(new String[] {"-server", "-h"});

        assertTrue(options.isHelp());
        assertEquals(StartupOptions.Mode.SERVER, options.getMode());
    }

    @Test
    void parse_should_fail_when_unknown_flag_given() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-cli", "--nope"}));

        assertTrue(error.getMessage().contains("未知参数"));
    }

    @Test
    void parse_should_hint_when_lowercase_v_given() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-cli", "-v"}));

        assertTrue(error.getMessage().contains("-V"));
        assertTrue(error.getMessage().contains("--verbose"));
    }

    @Test
    void parse_should_fail_when_key_value_form_given() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> StartupOptionsParser.parse(new String[] {"-cli", "--port=9096"}));

        assertTrue(error.getMessage().contains("--key value"));
    }

    @Test
    void parse_should_fail_when_argument_is_null() {
        assertThrows(JellyfishException.class, () -> StartupOptionsParser.parse(new String[] {"-cli", null}));
    }

    @Test
    void parse_should_default_to_tui_when_args_null() {
        // args 为 null 与空数组同义：都没有「用户意图」可循，因此走同一条兜底
        assertEquals(StartupOptions.Mode.TUI, StartupOptionsParser.parse(null).getMode());
    }

    @Test
    void usage_should_describe_all_modes_and_exit_codes() {
        String usage = StartupOptionsParser.usage();

        assertTrue(usage.contains("-cli"));
        assertTrue(usage.contains("-tui"));
        assertTrue(usage.contains("-server"));
        assertTrue(usage.contains("stdout"));
        assertTrue(usage.contains("stderr"));
        assertTrue(usage.contains("退出码"));
    }

    @Test
    void usage_should_mention_bare_run_defaults_to_tui() {
        // 用法文本里不写这一条，用户就没有任何地方能发现「不给模式也行」
        assertTrue(StartupOptionsParser.usage().contains("默认 -tui"), StartupOptionsParser.usage());
    }
}
