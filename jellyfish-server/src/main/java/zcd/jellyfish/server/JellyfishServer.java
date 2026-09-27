package zcd.jellyfish.server;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.handler.ApprovalHandlers;
import zcd.jellyfish.server.handler.ChatHandler;
import zcd.jellyfish.server.handler.CommandHandlers;
import zcd.jellyfish.server.handler.HealthHandler;
import zcd.jellyfish.server.handler.SessionHandlers;
import zcd.jellyfish.server.http.Router;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP 服务外壳：把内核的各个门面接到 Undertow 上，并管住「起 / 停 / 阻塞」。
 * <p>
 * <b>它做什么、不做什么</b>：只做装配（路由表 → 处理器）与生命周期。请求的业务逻辑全在
 * {@code handler} 包里，HTTP 形状全在 {@code http} 包里——这样「服务怎么起」与「接口怎么答」
 * 可以分别被测试，而本类剩下的大多是几行接线。
 * <p>
 * <b>关闭顺序为什么必须由本类自己保证</b>：{@code Launcher} 也会注册一个关闭钩子去调
 * {@code AgentHarness.shutdown()}，两个钩子的执行次序在 JVM 里没有保证。本类的钩子把
 * 「先停 HTTP、再放行 {@link #awaitShutdown()}」做完，从而让 {@code run()} 返回后
 * {@code Launcher} 的 {@code finally} 才去收内核——顺序是确定的。即便此刻另一个钩子已经抢先
 * 收了内核，{@code shutdown()} 也是幂等的，最坏结果只是正在流式返回的请求被中断。
 * <p>
 * <b>为什么审批者归本类挂载</b>：它必须与 HTTP 服务的存活期一致——HTTP 停了就没人能裁决，
 * 此时未决请求必须被 {@code detach()} 一次性拒绝，否则 react 线程会一直等到审批超时。
 *
 * @author zcd
 */
public final class JellyfishServer {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(JellyfishServer.class);

    /** 运行参数。 */
    private final ServerConfig config;

    /** 智能入口。 */
    private final AgentHarness harness;

    /** 会话域服务。 */
    private final SessionManager sessions;

    /** 命令域服务。 */
    private final CommandManager commands;

    /** agent 门面。 */
    private final AgentManager agents;

    /** 模型门面。 */
    private final ModelManager models;

    /** 审批桥。 */
    private final ApprovalBridge approvals;

    /** 健康检查汇总。 */
    private final HealthCheck healthCheck;

    /** 在途回合表。 */
    private final SessionTurns turns = new SessionTurns();

    /** 停止信号。 */
    private final CountDownLatch shutdown = new CountDownLatch(1);

    /** 是否已停止，保证 {@link #stop()} 幂等。 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** Undertow 实例，{@link #start()} 之前为 {@code null}。 */
    private Undertow server;

    /** 关闭钩子，{@link #start()} 时注册。 */
    private Thread hook;

    /**
     * 构造服务外壳。
     *
     * @param config      运行参数，不可为 {@code null}
     * @param harness     智能入口，不可为 {@code null}
     * @param sessions    会话域服务，不可为 {@code null}
     * @param commands    命令域服务，不可为 {@code null}
     * @param agents      agent 门面，不可为 {@code null}
     * @param models      模型门面，不可为 {@code null}
     * @param approvals   内核审批通道，不可为 {@code null}
     * @param healthCheck 健康检查汇总，不可为 {@code null}
     */
    public JellyfishServer(ServerConfig config, AgentHarness harness, SessionManager sessions,
                           CommandManager commands, AgentManager agents, ModelManager models,
                           ApprovalChannel approvals, HealthCheck healthCheck) {
        this.config = config;
        this.harness = harness;
        this.sessions = sessions;
        this.commands = commands;
        this.agents = agents;
        this.models = models;
        this.approvals = new ApprovalBridge(approvals);
        this.healthCheck = healthCheck;
    }

    /**
     * 启动 HTTP 服务。
     * <p>
     * 端口被占用等绑定失败会被包装成 {@link JellyfishException}，由外壳翻译成「启动失败」退出码——
     * 「端口写错了」与「服务跑挂了」对脚本是两类事。
     *
     * @throws JellyfishException 绑定失败时抛出
     */
    public void start() {
        approvals.attach();
        HttpHandler router = buildRouter();
        try {
            server = Undertow.builder()
                    .addHttpListener(config.getPort(), config.getHost())
                    .setWorkerThreads(config.getWorkerThreads())
                    .setHandler(router)
                    .build();
            server.start();
        } catch (RuntimeException e) {
            approvals.detach();
            throw new JellyfishException("启动 HTTP 服务失败（" + config.getHost() + ":" + config.getPort()
                    + "）：" + e.getMessage(), e);
        }
        LOG.info("已监听 http://{}:{}（无鉴权，默认仅回环；对外开放请显式 --host）",
                config.getHost(), boundPort());
        hook = new Thread(this::stop, "jellyfish-server-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /**
     * 阻塞直到 {@link #stop()} 被调用。
     *
     * @throws InterruptedException 等待被中断时抛出
     */
    public void awaitShutdown() throws InterruptedException {
        shutdown.await();
    }

    /**
     * 停止 HTTP 服务、摘下审批者并放行 {@link #awaitShutdown()}。
     * <p>
     * 幂等：重复调用（钩子 + {@code finally} 各一次）只有第一次生效。
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        if (server != null) {
            try {
                server.stop();
            } catch (RuntimeException e) {
                LOG.warn("停止 HTTP 服务时出错（继续关闭）：{}", e.getMessage());
            }
        }
        approvals.detach();
        shutdown.countDown();
        removeHook();
    }

    /**
     * 获取实际绑定端口。
     * <p>
     * 与配置端口不同的情形只有一种：配置为 0（由系统分配）。测试依赖这一点拿到随机端口。
     *
     * @return 实际绑定端口；尚未启动时返回配置端口
     */
    public int boundPort() {
        if (server == null) {
            return config.getPort();
        }
        InetSocketAddress address = (InetSocketAddress) server.getListenerInfo().get(0).getAddress();
        return address.getPort();
    }

    /**
     * 装配路由表。
     *
     * @return 路由器
     */
    private HttpHandler buildRouter() {
        SessionHandlers sessionHandlers = new SessionHandlers(config, sessions, agents, models, turns);
        ChatHandler chatHandler = new ChatHandler(harness, sessions, turns, config, approvals);
        CommandHandlers commandHandlers = new CommandHandlers(commands, config);
        ApprovalHandlers approvalHandlers = new ApprovalHandlers(approvals, config);
        HealthHandler healthHandler = new HealthHandler(healthCheck);
        return new Router()
                .route("POST", "/sessions", sessionHandlers::create)
                .route("GET", "/sessions", sessionHandlers::list)
                .route("GET", "/sessions/{id}", sessionHandlers::get)
                .route("DELETE", "/sessions/{id}", sessionHandlers::delete)
                .route("POST", "/sessions/{id}/cancel", sessionHandlers::cancel)
                .route("POST", "/sessions/{id}/chat", chatHandler::handle)
                .route("POST", "/sessions/{id}/commands", commandHandlers::execute)
                .route("GET", "/commands", commandHandlers::list)
                .route("GET", "/commands/{name}/options", commandHandlers::options)
                .route("GET", "/approvals", approvalHandlers::get)
                .route("POST", "/approvals/{requestId}", approvalHandlers::decide)
                .route("GET", "/health", healthHandler::handle);
    }

    /**
     * 摘除关闭钩子；JVM 已在关闭中时忽略异常。
     */
    private void removeHook() {
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            LOG.debug("JVM 正在关闭，无需摘除关闭钩子");
        } catch (RuntimeException e) {
            LOG.warn("关闭钩子摘除失败，继续关闭流程：{}", e.getMessage());
        }
    }
}
