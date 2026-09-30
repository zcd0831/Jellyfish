package zcd.jellyfish.cli.mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.cli.ExitCodes;
import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.ConsoleIO;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.JellyfishServer;
import zcd.jellyfish.server.ServerConfig;

import java.util.Map;
import java.util.Objects;

/**
 * Server 模式：以 HTTP 服务运行，基于 Undertow，对外暴露能力接口。
 * <p>
 * <b>本类只做接线</b>：把内核门面与启动参数装进 {@link JellyfishServer}，把它的生命周期收成退出码。
 * 路由、SSE、审批桥全在 {@code jellyfish-server} 里——{@code Launcher} 与启动参数解析一行不用改，
 * 与 {@code CliRunMode} / {@code TuiRunMode} 保持对称。
 * <p>
 * <b>为什么不把本类放进 {@code jellyfish-server}</b>：{@code RunMode}、{@code StartupOptions} 与
 * {@code ExitCodes} 都定义在 {@code jellyfish-cli}，放进去会形成 {@code cli → server → cli} 循环依赖。
 * <p>
 * <b>启动参数在 Server 下的含义</b>：{@code --port} / {@code --host} 决定绑定；{@code --agent} /
 * {@code --model} / {@code --mode} 是<b>新建会话的默认值</b>（落进 {@link ServerConfig}），
 * 不在启动期落到任何会话上；{@code --session} 已在参数解析阶段判为用法错误。
 * <p>
 * <b>为什么绑定失败退 3 而不是 4</b>：端口被占用是「启动条件不具备」，与配置写错同类，脚本应当直接放弃；
 * 报成运行期失败会让调用方以为是服务跑到一半挂了。
 *
 * @author zcd
 */
public final class ServerRunMode implements RunMode {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ServerRunMode.class);

    /** 智能入口。 */
    private final AgentHarness harness;

    /** 命令域服务。 */
    private final CommandManager commands;

    /** 会话域服务。 */
    private final SessionManager sessions;

    /** 模型门面。 */
    private final ModelManager models;

    /** agent 门面。 */
    private final AgentManager agents;

    /** 人工审批通道。 */
    private final ApprovalChannel approvals;

    /** 健康检查汇总。 */
    private final HealthCheck healthCheck;

    /** 输出面板。 */
    private final ConsoleIO console;

    /**
     * 构造 Server 模式。
     *
     * @param harness     智能入口，不可为 {@code null}
     * @param commands    命令域服务，不可为 {@code null}
     * @param sessions    会话域服务，不可为 {@code null}
     * @param models      模型门面，不可为 {@code null}
     * @param agents      agent 门面，不可为 {@code null}
     * @param approvals   人工审批通道，不可为 {@code null}
     * @param healthCheck 健康检查汇总，不可为 {@code null}
     * @param console     输出面板，不可为 {@code null}
     */
    public ServerRunMode(AgentHarness harness, CommandManager commands, SessionManager sessions,
                         ModelManager models, AgentManager agents, ApprovalChannel approvals,
                         HealthCheck healthCheck, ConsoleIO console) {
        this.harness = Objects.requireNonNull(harness, "harness must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.agents = Objects.requireNonNull(agents, "agents must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck must not be null");
        this.console = Objects.requireNonNull(console, "console must not be null");
    }

    @Override
    public RuntimeInfo.Shell shell() {
        // HTTP 服务：没有可交互界面，但具备审批通道（审批走 HTTP 桥）
        return RuntimeInfo.Shell.SERVER;
    }

    @Override
    public int run(StartupOptions options) {
        ServerConfig config = ServerConfig.builder(options.getHost(), options.getPort())
                .apiKey(resolveApiKey(options, System.getenv()))
                .build();
        JellyfishServer server = new JellyfishServer(config, harness, sessions, commands, agents, models,
                approvals, healthCheck);
        try {
            server.start();
        } catch (JellyfishException e) {
            // 绑定失败是启动条件不具备（退 3），不是跑挂了（退 4）
            LOG.error("Server 启动失败：{}", e.getMessage(), e);
            console.writeErrLine("启动失败：" + e.getMessage());
            return ExitCodes.STARTUP_ERROR;
        }
        try {
            server.awaitShutdown();
            return ExitCodes.OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExitCodes.RUNTIME_ERROR;
        } finally {
            server.stop();
        }
    }

    /**
     * 解析 API key：命令行参数优先，其次环境变量。
     * <p>
     * <b>为什么参数优先</b>：显式给的应当生效。若反过来让环境变量盖掉参数，用户会遇到「我明明传了」
     * 却查不出原因的 401，而排查方向（一个进程级的环境变量）恰恰是最不容易想到的那个。
     * <p>
     * <b>为什么用环境变量提一句 INFO</b>：环境变量是推荐做法（不进 {@code ps}、不进 shell history），
     * 而用户看不到「它被读到了」这件事；日志里说一句，才谈得上「推荐」。
     *
     * @param options 启动参数，不可为 {@code null}
     * @param env     环境变量表，可为 {@code null}（无环境变量）
     * @return API key；未提供时返回 {@code null}
     */
    static String resolveApiKey(StartupOptions options, Map<String, String> env) {
        if (options.getApiKey() != null && !options.getApiKey().trim().isEmpty()) {
            return options.getApiKey().trim();
        }
        String fromEnv = env == null ? null : env.get(ServerConfig.ENV_API_KEY);
        if (fromEnv == null || fromEnv.trim().isEmpty()) {
            return null;
        }
        LOG.info("API key 取自环境变量 {}", ServerConfig.ENV_API_KEY);
        return fromEnv.trim();
    }
}
