package zcd.jellyfish.cli.mode;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.cli.ExitCodes;
import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.RecordingConsoleIO;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.ServerConfig;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link ServerRunMode} 的构造校验与绑定失败分类。
 * <p>
 * <b>为什么不测「正常跑起来」</b>：那需要一条真 HTTP 连接，属于端到端用例（{@code -Pserver-it}）。
 * 这里只钉住「绑定失败 → 退 3」这条分类——它正是「端口写错」与「服务跑挂了」的边界。
 *
 * @author zcd
 */
class ServerRunModeTest {

    /** agent 门面。 */
    private final AgentManager agents = mock(AgentManager.class);

    /** 模型门面。 */
    private final ModelManager models = mock(ModelManager.class);

    /** 命令域服务。 */
    private final CommandManager commands = mock(CommandManager.class);

    /** 会话域服务。 */
    private final SessionManager sessions = mock(SessionManager.class);

    /** 智能入口。 */
    private final AgentHarness harness = mock(AgentHarness.class);

    /** 审批通道。 */
    private final ApprovalChannel approvals = new ApprovalChannel();

    /** 健康检查汇总。 */
    private final HealthCheck healthCheck = new HealthCheck(Collections.emptyList());

    /**
     * 构造模式实例。
     *
     * @param console 输出面板
     * @return 模式实例
     */
    private ServerRunMode mode(RecordingConsoleIO console) {
        return new ServerRunMode(harness, commands, sessions, models, agents, approvals, healthCheck, console);
    }

    @Test
    void constructor_should_reject_null_collaborators() {
        RecordingConsoleIO console = new RecordingConsoleIO(null);
        assertThrows(NullPointerException.class,
                () -> new ServerRunMode(null, commands, sessions, models, agents, approvals, healthCheck, console));
        assertThrows(NullPointerException.class,
                () -> new ServerRunMode(harness, null, sessions, models, agents, approvals, healthCheck, console));
        assertThrows(NullPointerException.class,
                () -> new ServerRunMode(harness, commands, sessions, models, agents, approvals, healthCheck, null));
    }

    @Test
    void resolveApiKey_should_prefer_cli_argument_over_environment() {
        // 显式给的必须生效：反过来会让「我明明传了」变成一个查不出原因的 401
        StartupOptions options = StartupOptions.builder(StartupOptions.Mode.SERVER).apiKey("from-flag").build();

        String key = ServerRunMode.resolveApiKey(options, Collections.singletonMap(
                ServerConfig.ENV_API_KEY, "from-env"));

        assertEquals("from-flag", key);
    }

    @Test
    void resolveApiKey_should_fallBackToEnvironment() {
        StartupOptions options = StartupOptions.builder(StartupOptions.Mode.SERVER).build();

        String key = ServerRunMode.resolveApiKey(options, Collections.singletonMap(
                ServerConfig.ENV_API_KEY, "  from-env  "));

        assertEquals("from-env", key);
    }

    @Test
    void resolveApiKey_should_return_null_when_neitherGiven() {
        StartupOptions options = StartupOptions.builder(StartupOptions.Mode.SERVER).build();

        assertNull(ServerRunMode.resolveApiKey(options, Collections.<String, String>emptyMap()));
        assertNull(ServerRunMode.resolveApiKey(options, null));
        assertNull(ServerRunMode.resolveApiKey(options, Collections.singletonMap(ServerConfig.ENV_API_KEY, "   ")));
    }

    @Test
    void run_should_return_startup_error_when_bind_fails() {
        // 用「非本机地址」构造绑定失败。刻意不用「占用端口」：macOS 上 Undertow 会设 SO_REUSEPORT，
        // 已占端口仍能绑上（实测），那样测试会挂在 awaitShutdown 上。
        RecordingConsoleIO console = new RecordingConsoleIO(null);

        int code = mode(console).run(StartupOptions.builder(StartupOptions.Mode.SERVER)
                .port(0).host("198.51.100.1").build());

        assertEquals(ExitCodes.STARTUP_ERROR, code);
        assertTrue(console.err().contains("启动失败"), console.err());
    }
}
