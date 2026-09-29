package zcd.jellyfish.plugin.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 官方 MCP 客户端插件：连上 stdio MCP server，把它的工具接入内核。
 * <p>
 * <b>连接在启动之后才发生，且不在启动线程上做完</b>：MCP 的工具清单只有连上才知道，
 * 而连上要起进程、要握手、要等对面的回应。把这些塞进 {@code start()} 的同步路径，
 * 等于让「某个 server 的安装有问题」变成「内核起不来」——这与脚本桥接插件
 * 「{@code start()} 期零进程、解释器缺失不影响内核启动」是同一条纪律。
 * <p>
 * <b>但也不完全不等</b>：{@code startupWaitSeconds}（缺省 5 秒）内会等一等。
 * 一个只等零秒的实现会让「第一次问它问题时工具还没到」成为每个人的第一印象，
 * 而绝大多数本地 server（{@code npx} 之类）一两秒内就绪。超过这个窗口就转异步，
 * 连接成功时工具会自己出现——{@code ToolCatalog} 每轮现取注册表，不需要任何人通知它。
 * <p>
 * <b>工具是运行期注册的</b>：这是本插件依赖「注册窗口是插件存活期」那条契约的地方，
 * 也是它与所有既有插件的根本差别——那些插件的能力在 {@code start()} 里就是已知的。
 * <p>
 * <b>不支持 sampling / elicitation</b>：{@code initialize} 里不声明，真被请求时回一条明确的
 * {@code -32601}。这两个能力要求「插件反过来向内核要东西」，而本项目的能力边界是
 * 「插件只能注册回调、订阅事件、发布事件」。声明了却办不到比不声明更糟：
 * server 会按「客户端支持」去规划它的行为。
 *
 * @author zcd
 */
public final class McpPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpPlugin.class);

    /** {@code /mcp} 命令名。 */
    private static final String COMMAND_MCP = "mcp";

    /** 当前配置。 */
    private volatile McpConfig config;

    /** 共享状态。 */
    private volatile McpRegistry registry;

    /** 二进制内容落盘器。 */
    private volatile McpMediaSpill spill;

    /** 工具注册器。 */
    private volatile McpToolRegistrar registrar;

    /** 已建立的连接；连接线程会往里加，停止时从这里收。 */
    private final List<McpServerConnection> connections =
            Collections.synchronizedList(new ArrayList<McpServerConnection>());

    /** 连接线程。 */
    private volatile Thread connector;

    /** 是否正在停止：连接线程与工具接收方都靠它提前退出。 */
    private volatile boolean stopping;

    @Override
    public synchronized void start(PluginContext context) {
        // 先解析配置：值非法属配置错误，启动期就该让人看见，而不是压到第一次调用
        McpConfig parsed = McpConfig.from(context.configuration());
        McpRegistry parsedRegistry = new McpRegistry();
        for (McpServerConfig server : parsed.servers()) {
            parsedRegistry.register(server.id(),
                    server.enabled() ? McpRegistry.State.PENDING : McpRegistry.State.DISABLED,
                    server.enabled() ? "" : "配置里 enabled=false");
        }
        config = parsed;
        registry = parsedRegistry;
        spill = new McpMediaSpill(McpMediaSpill.defaultBaseDirectory());
        registrar = new McpToolRegistrar(context, parsedRegistry);
        stopping = false;
        // 台账命令无论有没有 server 都注册：连不上时的唯一答案就在这里
        context.handle(CommandRequest.class, COMMAND_MCP,
                new CommandDescriptor("查看 MCP server 的连接状态与已注册工具", null, null, false),
                request -> CommandResult.ok(McpLedger.render(parsed, parsedRegistry)));
        if (parsed.askWriteTools()) {
            context.contribute(PermissionCheckRequest.class,
                    new McpPermissionContribution(parsedRegistry, parsed));
        }
        if (parsed.enabledServers().isEmpty()) {
            LOG.warn("mcp 插件已启动但没有任何启用的 server：在 "
                    + "plugins.configurations.jellyfish-mcp.servers 里配置");
            return;
        }
        startConnector(parsed, parsedRegistry);
    }

    @Override
    public synchronized void stop() {
        stopping = true;
        Thread thread = connector;
        connector = null;
        if (thread != null) {
            thread.interrupt();
        }
        for (McpServerConnection connection : new ArrayList<McpServerConnection>(connections)) {
            closeQuietly(connection);
        }
        connections.clear();
        McpToolRegistrar currentRegistrar = registrar;
        if (currentRegistrar != null) {
            currentRegistrar.closeAll();
        }
        McpMediaSpill currentSpill = spill;
        if (currentSpill != null) {
            currentSpill.cleanup();
        }
        config = null;
        registry = null;
        registrar = null;
        spill = null;
    }

    /**
     * 起连接线程并可选地等待一小段时间。
     * <p>
     * <b>等的是「连接线程收尾」而不是「每个 server 都连上」</b>：连接线程按顺序处理每个 server，
     * 一个 server 卡到超时不应该让后面的一个都没机会。因此等待窗口一到就返回，
     * 连接线程继续在后台跑完。
     *
     * @param parsed         配置
     * @param parsedRegistry 共享状态
     */
    private void startConnector(McpConfig parsed, McpRegistry parsedRegistry) {
        CountDownLatch finished = new CountDownLatch(1);
        Thread thread = new Thread(() -> connectAll(parsed, parsedRegistry, finished), "mcp-connect");
        thread.setDaemon(true);
        connector = thread;
        thread.start();
        int waitSeconds = parsed.startupWaitSeconds();
        if (waitSeconds > 0) {
            try {
                finished.await(waitSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("mcp 插件已启动: servers={} 已连接={} 工具={}", parsed.enabledServers().size(),
                countConnected(parsedRegistry), countTools(parsedRegistry));
    }

    /**
     * 逐个连接 server，并把工具注册进内核。
     * <p>
     * <b>逐个而不是并发</b>：一个 server 的连接失败要能被独立记下（哪个失败、为什么），
     * 而并发连接会把日志与状态混在一起。server 数量是个人配置级别的量级，串行足够。
     *
     * @param parsed         配置
     * @param parsedRegistry 共享状态
     * @param finished       连接线程收尾信号
     */
    private void connectAll(McpConfig parsed, McpRegistry parsedRegistry, CountDownLatch finished) {
        try {
            for (McpServerConfig server : parsed.enabledServers()) {
                if (stopping) {
                    return;
                }
                connectOne(parsed, parsedRegistry, server);
            }
        } finally {
            finished.countDown();
        }
    }

    /**
     * 连接单个 server。
     *
     * @param parsed         配置
     * @param parsedRegistry 共享状态
     * @param server         服务配置
     */
    private void connectOne(McpConfig parsed, McpRegistry parsedRegistry, McpServerConfig server) {
        // 工具接收方需要「它所属的那个连接」，而连接又要先有接收方才能构造：
        // 用一个一元素数组把这两件事的顺序解开。数组是 final 的，每个 server 一份
        final McpServerConnection[] holder = new McpServerConnection[1];
        Consumer<List<McpToolDefinition>> sink = tools -> {
            McpServerConnection connection = holder[0];
            if (connection == null || stopping) {
                return;
            }
            try {
                registrar.apply(connection, server, tools);
            } catch (RuntimeException e) {
                LOG.warn("注册 MCP 工具失败: id={}", server.id(), e);
                parsedRegistry.noteWarning(server.id(), "工具注册失败：" + e.getMessage());
            }
        };
        McpServerConnection connection = new McpServerConnection(server, parsed, parsedRegistry, sink, spill);
        holder[0] = connection;
        connections.add(connection);
        try {
            connection.connect();
        } catch (RuntimeException e) {
            // 单个 server 连不上不该让插件 FAILED：其余 server 与「没有 MCP」都还能用。
            // 失败状态由连接对象自己记（它才知道握手停在哪一步），这里只负责收尾资源
            closeQuietly(connection);
        }
    }

    /**
     * 安静地关闭一条连接。
     *
     * @param connection 连接，可为 {@code null}
     */
    private static void closeQuietly(McpServerConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException e) {
            LOG.warn("关闭 MCP 连接失败: id={}", connection.serverId(), e);
        }
    }

    /**
     * 统计已连接的 server 数。
     *
     * @param registryOfRun 共享状态
     * @return 数量
     */
    private static int countConnected(McpRegistry registryOfRun) {
        int count = 0;
        for (McpRegistry.ServerStatus status : registryOfRun.snapshot()) {
            if (status.state() == McpRegistry.State.CONNECTED) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计已注册工具数。
     *
     * @param registryOfRun 共享状态
     * @return 数量
     */
    private static int countTools(McpRegistry registryOfRun) {
        int count = 0;
        for (McpRegistry.ServerStatus status : registryOfRun.snapshot()) {
            count += status.toolCount();
        }
        return count;
    }
}
