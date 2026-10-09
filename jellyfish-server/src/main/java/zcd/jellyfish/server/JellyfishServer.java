package zcd.jellyfish.server;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.core.runtime.RunEventBus;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.handler.ApprovalHandlers;
import zcd.jellyfish.server.handler.AskHandlers;
import zcd.jellyfish.server.handler.ChatHandler;
import zcd.jellyfish.server.handler.CommandHandlers;
import zcd.jellyfish.server.handler.HealthHandler;
import zcd.jellyfish.server.handler.SessionHandlers;
import zcd.jellyfish.server.http.ApiKeyGuard;
import zcd.jellyfish.server.http.OriginGuard;
import zcd.jellyfish.server.http.Router;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** 会话提交服务：分流与起回合的唯一入口。 */
    private final ConversationService conversations;

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

    /** 提问桥：把内核提问通道的头槽位语义翻译成 HTTP 语义。 */
    private final AskBridge asks;

    /** 健康检查汇总。 */
    private final HealthCheck healthCheck;

    /** 在途回合表（内核拥有）：只用于取消端点。 */
    private final TurnRegistry turns;

    /** 可靠 lane：交给 chat 处理器做订阅。 */
    private final ShellStreams streams;

    /** run 事件总线：交给 chat 处理器做订阅。 */
    private final RunEventBus runEvents;

    /** 停止信号。 */
    private final CountDownLatch shutdown = new CountDownLatch(1);

    /** 是否已停止，保证 {@link #stop()} 幂等。 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** Undertow 实例，{@link #start()} 之前为 {@code null}。 */
    private Undertow server;

    /**
     * SSE 写循环的专用线程池，构造时建好、{@link #stop()} 时关停。
     * <p>
     * <b>容量取 {@code maxStreams}</b>：一条流在它整个生命周期里占一个线程，而入口处已有
     * 并发流许可把数量限在 {@code maxStreams} —— 两者相等时「拿到许可一定拿得到线程」成立，
     * 也就不会出现「许可拿到了、任务却排在池里等线程」这种白等。
     */
    private final ExecutorService streamExecutor;

    /** 关闭钩子，{@link #start()} 时注册。 */
    private Thread hook;

    /**
     * 构造服务外壳。
     *
     * @param config      运行参数，不可为 {@code null}
     * @param conversations 会话提交服务，不可为 {@code null}
     * @param sessions    会话域服务，不可为 {@code null}
     * @param commands    命令域服务，不可为 {@code null}
     * @param agents      agent 门面，不可为 {@code null}
     * @param models      模型门面，不可为 {@code null}
     * @param approvals   内核审批通道，不可为 {@code null}
     * @param asks        内核提问通道，不可为 {@code null}
     * @param healthCheck 健康检查汇总，不可为 {@code null}
     * @param turns       在途回合表（内核拥有），不可为 {@code null}
     * @param streams     可靠 lane，不可为 {@code null}
     * @param runEvents   run 事件总线，不可为 {@code null}
     */
    public JellyfishServer(ServerConfig config, ConversationService conversations, SessionManager sessions,
                           CommandManager commands, AgentManager agents, ModelManager models,
                           ApprovalChannel approvals, AskChannel asks, HealthCheck healthCheck,
                           TurnRegistry turns, ShellStreams streams, RunEventBus runEvents) {
        this.config = config;
        this.conversations = conversations;
        this.sessions = sessions;
        this.commands = commands;
        this.agents = agents;
        this.models = models;
        this.approvals = new ApprovalBridge(approvals);
        this.asks = new AskBridge(asks);
        this.healthCheck = healthCheck;
        this.turns = turns;
        this.streams = streams;
        this.runEvents = runEvents;
        this.streamExecutor = Executors.newFixedThreadPool(Math.max(1, config.getMaxStreams()),
                new SseThreadFactory());
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
        requireUsableExposure();
        approvals.attach();
        // 提问答复者同批挂上：服务模式把提问搬到 HTTP 层，与审批同一条路
        asks.attach();
        HttpHandler router = buildRouter();
        // 来源校验在鉴权之外单独一层：它挡的是「浏览器被别的页面指使去写本服务」，
        // 与「调用方有没有密钥」是两件事——没有密钥的本地服务同样需要它
        router = new OriginGuard(router);
        if (config.getApiKey() != null) {
            // 包在路由外面：新接口默认就被保护，例外只能是显式声明的（探活）
            router = new ApiKeyGuard(config.getApiKey(), router);
        }
        try {
            server = Undertow.builder()
                    .addHttpListener(config.getPort(), config.getHost())
                    .setWorkerThreads(config.getWorkerThreads())
                    .setHandler(router)
                    .build();
            server.start();
        } catch (RuntimeException e) {
            approvals.detach();
            asks.detach();
            throw new JellyfishException("启动 HTTP 服务失败（" + config.getHost() + ":" + config.getPort()
                    + "）：" + e.getMessage(), e);
        }
        logReady();
        hook = new Thread(this::stop, "jellyfish-server-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /**
     * 校验监听配置不会把无鉴权的能力敞开给非本机。
     * <p>
     * <b>为什么是拒绝启动而不是继续用 WARN</b>：无密钥 + 非回环等于把「建会话、跑命令、读全部会话正文、
     * 批准他人的工具调用」交给同一网段的任何人，而唯一的提示是一行 WARN——那是最容易被忽略、
     * 又最不该被忽略的一种配置。退出码 3 让脚本当场停下，比跑到一半被人扫到要好。
     * <p>
     * <b>回环仍然允许无密钥</b>：这是刻意的缺省，只服务「本机跑一次」。
     *
     * @throws JellyfishException 绑定了非回环地址但未配 API key 时抛出
     */
    private void requireUsableExposure() {
        requireUsableExposure(config);
    }

    /**
     * 校验监听配置不会把无鉴权的能力敞开给非本机（实现）。
     * <p>
     * 抽成静态是为了能在不起服务的前提下单测这条判据——它是一段纯配置检查，
     * 而 {@code start()} 需要全套协作者。
     *
     * @param config 运行参数，不可为 {@code null}
     * @throws JellyfishException 绑定了非回环地址但未配 API key 时抛出
     */
    static void requireUsableExposure(ServerConfig config) {
        if (config.getApiKey() != null || isLoopback(config.getHost())) {
            return;
        }
        throw new JellyfishException("拒绝启动：绑定 " + config.getHost()
                + " 却未配 API key——任何能访问该端口的人都能建会话、跑命令、读全部会话正文。"
                + "请配 " + ServerConfig.ENV_API_KEY + " 环境变量或 --api-key，或改回 --host 127.0.0.1");
    }

    /**
     * 判断监听地址是否只服务本机。
     * <p>
     * 判据用 {@link InetAddress#isLoopbackAddress()} 而不是字符串比对：{@code localhost}、
     * {@code ::1}、{@code 127.0.0.2} 都是回环，而它们与 {@code 127.0.0.1} 不同名。
     * 解析不出来时按「非回环」处理（拒绝），因为那多半是写错的主机名——让它在启动期就暴露。
     *
     * @param host 监听地址，不可为 {@code null}
     * @return 只服务本机返回 {@code true}
     */
    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            LOG.warn("监听地址无法解析，按「非回环」处理: host={}", host);
            return false;
        }
    }

    /**
     * 打印启动后的监听信息与鉴权状态。
     * <p>
     * <b>为什么把鉴权状态放在这里明说</b>：少了这一行，「以为配了密钥」与「其实没配」在现象上都是
     * 「能访问」——而那正是最危险的一种安静。
     * <p>
     * <b>没配密钥时用 WARN 级</b>：默认日志级别就是 WARN（正常一次对话不该有噪音），因此这是
     * 「一定会被看见」的那一档；而配好了密钥是正常状态，用 INFO，不该在默认级别下报警。
     */
    private void logReady() {
        if (config.getApiKey() == null) {
            LOG.warn("已监听 http://{}:{}（**无鉴权**：任何能访问该端口的人都能建会话、跑命令、读全部会话正文；"
                    + "默认仅回环，对外开放请显式配 API key 并限定 --host）", config.getHost(), boundPort());
            return;
        }
        LOG.info("已监听 http://{}:{}（已启用 API key 鉴权；GET /health 不校验）",
                config.getHost(), boundPort());
        if (config.getApiKey().length() < ServerConfig.MIN_RECOMMENDED_API_KEY_LENGTH) {
            LOG.warn("API key 只有 {} 个字符，建议至少 {} 位的随机串",
                    config.getApiKey().length(), ServerConfig.MIN_RECOMMENDED_API_KEY_LENGTH);
        }
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
        // 提问答复者同批摘下：未决提问立刻收敛成「这次没问到」，等待线程随即继续
        asks.detach();
        // 不等写循环收敛：它们可能正阻塞在一条不读的客户端上，而中断唤不醒阻塞写。
        // 它们是守护线程，且持有的是自己的资源（订阅已被 JVM 退出带走），因此这里只发个信号
        streamExecutor.shutdownNow();
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
        ChatHandler chatHandler = new ChatHandler(conversations, streams, turns, runEvents, sessions, config,
                approvals, asks, streamExecutor);
        CommandHandlers commandHandlers = new CommandHandlers(commands, sessions, config);
        ApprovalHandlers approvalHandlers = new ApprovalHandlers(approvals, sessions, config);
        AskHandlers askHandlers = new AskHandlers(asks, sessions, config);
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
                .route("GET", "/approvals", approvalHandlers::getAny)
                .route("GET", "/sessions/{id}/approvals", approvalHandlers::get)
                .route("POST", "/sessions/{id}/approvals/{requestId}", approvalHandlers::decide)
                .route("GET", "/asks", askHandlers::getAny)
                .route("GET", "/sessions/{id}/asks", askHandlers::get)
                .route("POST", "/sessions/{id}/asks/{requestId}", askHandlers::answer)
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
