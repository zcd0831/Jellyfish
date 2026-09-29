package zcd.jellyfish.plugin.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 与一个 MCP server 的连接：握手、请求/响应配对、通知处理、工具调用与收尾。
 * <p>
 * <b>读线程是唯一的分发点</b>：一条连接只有一条线程从子进程读消息，它按「有没有 id」把消息分给三处
 * ——带 id 带 method 的是 server 主动发来的请求，带 id 不带 method 的是我们某次请求的应答，
 * 不带 id 的是通知。这个分法就是 JSON-RPC 的全部路由规则，不需要额外的状态机。
 * <p>
 * <b>响应配对靠 id，不靠顺序</b>：server 可以并发处理多个请求，因此应答完全可能乱序到达。
 * 每个请求在发出前登记一个等待位，超时后<b>摘掉</b>等待位——迟到的应答按 id 找不到人，
 * 于是被计数并丢弃，而不是错误地唤醒一个已经在等别的东西的调用。
 * <p>
 * <b>不声明 sampling / elicitation，也不支持它们</b>：这两个能力是「server 反过来向客户端要东西」
 * （要一次模型补全 / 要用户回答）。本客户端在 {@code initialize} 里不声明它们，因此行为良好的 server
 * 不会发；真发了就回一条明确的 {@code -32601}，而不是挂在那里等。这与本项目「插件不能发起回调」
 * 是同一条边界。
 * <p>
 * 线程安全：{@link #callTool} 可被多条 {@code react} 线程并发调用。
 *
 * @author zcd
 */
final class McpServerConnection implements McpInvoker, AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpServerConnection.class);

    /** {@code tools/list} 分页的页数上限，防御性：分页游标不收敛时不该无限循环。 */
    private static final int MAX_LIST_PAGES = 20;

    /** 客户端名，写进 {@code initialize} 的 {@code clientInfo}。 */
    private static final String CLIENT_NAME = "jellyfish";

    /** 服务配置。 */
    private final McpServerConfig config;

    /** 全局配置。 */
    private final McpConfig global;

    /** 共享状态。 */
    private final McpRegistry registry;

    /** 工具清单变化时的接收方（注册与注销都归它）。 */
    private final Consumer<List<McpToolDefinition>> toolSink;

    /** 二进制内容落盘器。 */
    private final McpMediaSpill spill;

    /** 传输工厂：生产实现起子进程，测试注入内存通道。 */
    private final McpTransport.Factory transportFactory;

    /** 请求 id 序列。 */
    private final AtomicLong nextId = new AtomicLong();

    /**
     * 通知处理专用执行器。
     * <p>
     * <b>存在的唯一理由是避免自锁</b>：{@code tools/list_changed} 是在<b>读线程</b>上收到的，
     * 而重扫要去发一个 {@code tools/list} 请求并等应答——应答只能由同一条读线程投递。
     * 直接在读线程上做，就是一条线程等它自己。因此通知处理必须转到另一条线程上，
     * 这也正是「读线程只负责分发」那条规则的真正含义。
     */
    private final ExecutorService notificationWorker = createNotificationWorker();

    /** 在途请求：id → 等待位。 */
    private final Map<Long, Pending> pending = new ConcurrentHashMap<Long, Pending>();

    /** 子进程传输；未连接时为 {@code null}。 */
    private volatile McpTransport transport;

    /** 读线程。 */
    private volatile Thread readerThread;

    /** 是否已关闭。 */
    private volatile boolean closed;

    /** 是否已握手完成。 */
    private volatile boolean connected;

    /**
     * 构造连接。
     *
     * @param config   服务配置，不可为 {@code null}
     * @param global   全局配置，不可为 {@code null}
     * @param registry 共享状态，不可为 {@code null}
     * @param toolSink 工具清单接收方，不可为 {@code null}
     * @param spill    二进制内容落盘器，不可为 {@code null}
     */
    McpServerConnection(McpServerConfig config, McpConfig global, McpRegistry registry,
                        Consumer<List<McpToolDefinition>> toolSink, McpMediaSpill spill) {
        this(config, global, registry, toolSink, spill, McpStdioTransport::start);
    }

    /**
     * 构造连接，使用指定的传输工厂。
     *
     * @param config           服务配置，不可为 {@code null}
     * @param global           全局配置，不可为 {@code null}
     * @param registry         共享状态，不可为 {@code null}
     * @param toolSink         工具清单接收方，不可为 {@code null}
     * @param spill            二进制内容落盘器，不可为 {@code null}
     * @param transportFactory 传输工厂，不可为 {@code null}
     */
    McpServerConnection(McpServerConfig config, McpConfig global, McpRegistry registry,
                        Consumer<List<McpToolDefinition>> toolSink, McpMediaSpill spill,
                        McpTransport.Factory transportFactory) {
        this.config = config;
        this.global = global;
        this.registry = registry;
        this.toolSink = toolSink;
        this.spill = spill;
        this.transportFactory = transportFactory;
    }

    /**
     * 连接并握手：起子进程 → {@code initialize} → {@code notifications/initialized} → 拉工具清单。
     * <p>
     * <b>本方法是阻塞的，且只在连接线程上调用</b>：它会把进程启动与网络握手的耗时算进调用它的线程，
     * 因此绝不能在内核启动线程或 {@code react} 线程上直接调（见 {@code McpPlugin} 的连接线程）。
     *
     * @throws JellyfishException 启动失败、握手失败或工具清单拉取失败时抛出
     */
    void connect() {
        registry.noteState(config.id(), McpRegistry.State.CONNECTING, "启动进程并握手");
        try {
            connectInternal();
        } catch (RuntimeException e) {
            // 连接对象自己记录它的失败：状态转移只有它知道，让调用点再记一次只会多一份会漂移的文案
            registry.noteFailure(config.id(), e.getMessage());
            throw e;
        }
    }

    /**
     * 连接主体：起进程、起读线程、握手、拉工具清单。
     */
    private void connectInternal() {
        McpTransport started;
        try {
            started = transportFactory.start(config);
        } catch (IOException e) {
            throw new JellyfishException("启动 MCP server 失败: " + config.id()
                    + "（" + e.getMessage() + "）", e);
        }
        transport = started;
        startReader(started);
        long timeoutMillis = config.connectTimeoutSeconds() * 1000L;
        initialize(timeoutMillis);
        connected = true;
        registry.noteState(config.id(), McpRegistry.State.CONNECTED, "握手完成");
        refreshTools(timeoutMillis);
    }

    @Override
    public McpCallOutcome callTool(String originalToolName, Map<String, Object> arguments,
                                   long timeoutMillis, CancellationToken cancellationToken) {
        if (closed || !connected || transport == null) {
            throw new JellyfishException("MCP server 未连接: " + config.id());
        }
        ObjectNode params = McpJson.object();
        params.put("name", originalToolName);
        ObjectNode args = params.putObject("arguments");
        if (arguments != null) {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                JsonNode value = McpJson.valueToTree(entry.getValue());
                if (value != null) {
                    args.set(entry.getKey(), value);
                }
            }
        }
        long start = System.currentTimeMillis();
        JsonNode result = request(McpProtocol.METHOD_TOOLS_CALL, params, timeoutMillis,
                cancellationToken);
        boolean error = McpJson.bool(result, "isError", false);
        String text = McpContent.render(result, spill, config.id(), originalToolName);
        return new McpCallOutcome(text, error, System.currentTimeMillis() - start);
    }

    @Override
    public void close() {
        closed = true;
        connected = false;
        failAllPending("MCP server 已断开: " + config.id());
        notificationWorker.shutdownNow();
        McpTransport current = transport;
        transport = null;
        if (current != null) {
            current.close();
        }
        Thread reader = readerThread;
        if (reader != null) {
            try {
                reader.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        registry.removeServer(config.id());
        // 失败状态比「已停止」更有信息量：连不上的 server 不该在下一次 /mcp 里变成一句无头无尾的停止
        McpRegistry.ServerStatus status = registry.statusOf(config.id());
        if (status == null || status.state() != McpRegistry.State.FAILED) {
            registry.noteState(config.id(), McpRegistry.State.STOPPED, "已停止");
        }
    }

    /**
     * 获取服务标识。
     *
     * @return 标识
     */
    String serverId() {
        return config.id();
    }

    /**
     * 握手：发送 {@code initialize} 并确认版本，随后发 {@code notifications/initialized}。
     * <p>
     * <b>能力里只声明 {@code roots}</b>：声明了就得能应答，而 {@code roots/list} 我们确实答得上来
     * （返回进程工作目录）。{@code sampling} 与 {@code elicitation} 刻意不声明——本客户端做不到，
     * 声明了却办不到比不声明更糟：server 会按「客户端支持」去规划它的行为。
     *
     * @param timeoutMillis 握手超时毫秒数
     */
    private void initialize(long timeoutMillis) {
        ObjectNode params = McpJson.object();
        params.put("protocolVersion", McpProtocol.PROTOCOL_VERSION);
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.putObject("roots").put("listChanged", false);
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", "0.0.1");
        JsonNode result = request(McpProtocol.METHOD_INITIALIZE, params, timeoutMillis,
                CancellationToken.NONE);
        String serverVersion = McpJson.text(result, "protocolVersion", null);
        if (serverVersion != null && !McpProtocol.PROTOCOL_VERSION.equals(serverVersion)) {
            // 规范允许 server 回一个它支持的版本；不相等不是错误，但它决定了对面按哪套规则说话
            LOG.info("MCP server 协商到不同协议版本: id={} ours={} theirs={}", config.id(),
                    McpProtocol.PROTOCOL_VERSION, serverVersion);
        }
        notify(McpProtocol.METHOD_INITIALIZED, null);
    }

    /**
     * 起读线程。
     *
     * @param started 传输
     */
    private void startReader(McpTransport started) {
        Thread thread = new Thread(() -> readLoop(started), "mcp-read-" + config.id());
        thread.setDaemon(true);
        readerThread = thread;
        thread.start();
    }

    /**
     * 读循环：解析每一行并按 JSON-RPC 的路由规则分发。
     *
     * @param started 传输
     */
    private void readLoop(McpTransport started) {
        try {
            String line;
            while (!closed && (line = started.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                dispatch(line);
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("读取 MCP server 消息失败: id={}", config.id(), e);
            }
        } finally {
            if (!closed) {
                // 进程退出而插件没停：把在途请求叫醒，别让调用方一直等到超时
                connected = false;
                failAllPending("MCP server 进程已退出: " + config.id());
                registry.noteFailure(config.id(), "server 进程已退出");
            }
        }
    }

    /**
     * 分发一条消息。
     *
     * @param line 一行 JSON
     */
    private void dispatch(String line) {
        ObjectNode message;
        try {
            message = McpJson.parseObject(line);
        } catch (JellyfishException e) {
            // 解析不了的那一行只可能是服务端日志混了进来或对面不是 MCP server；
            // 丢掉它是安全的：协议没有「半条消息」这种状态，而留着它会毒化后续所有处理
            LOG.warn("忽略无法解析的 MCP 消息: id={} reason={}", config.id(), e.getMessage());
            return;
        }
        JsonNode id = message.get("id");
        String method = McpJson.text(message, "method", null);
        if (id == null || id.isNull()) {
            if (method != null) {
                handleNotification(method, message);
            }
            return;
        }
        if (method != null) {
            handleServerRequest(id, method);
            return;
        }
        completePending(id, message);
    }

    /**
     * 处理通知。
     *
     * @param method  方法名
     * @param message 完整消息
     */
    private void handleNotification(String method, ObjectNode message) {
        if (McpProtocol.METHOD_TOOLS_LIST_CHANGED.equals(method)) {
            // 转到另一条线程：读线程等不了它自己投递的应答（见 notificationWorker 的注释）
            try {
                notificationWorker.execute(this::refreshToolsAfterNotification);
            } catch (RejectedExecutionException e) {
                LOG.debug("MCP 通知处理已停止，忽略本次清单变化: id={}", config.id());
            }
            return;
        }
        LOG.debug("MCP 通知: id={} method={}", config.id(), method);
    }

    /**
     * 清单变化通知触发的重扫。
     * <p>
     * 失败只记告警、不把异常往外抛：它跑在通知处理线程上，抛出去除了变成一条无人可归因的堆栈之外
     * 没有任何作用，而下一次 {@code tools/list} 仍会照常发生。
     */
    private void refreshToolsAfterNotification() {
        try {
            refreshTools(config.callTimeoutSeconds() * 1000L);
            registry.noteState(config.id(), McpRegistry.State.CONNECTED, "工具清单已更新");
        } catch (RuntimeException e) {
            LOG.warn("刷新 MCP 工具清单失败: id={}", config.id(), e);
            registry.noteWarning(config.id(), "工具清单刷新失败：" + e.getMessage());
        }
    }

    /**
     * 处理 server 主动发来的请求。
     * <p>
     * <b>只认 {@code roots/list} 与 {@code ping}，其余一律回 {@code -32601}</b>：
     * 「回一条明确的错误」与「不回」在对面看来完全不同——后者会让 server 一直等到它自己的超时，
     * 而现场表现是「这个 server 的其他功能也变慢了」。
     *
     * @param id     请求 id 节点
     * @param method 方法名
     */
    private void handleServerRequest(JsonNode id, String method) {
        if (McpProtocol.METHOD_ROOTS_LIST.equals(method)) {
            respond(id, rootsResult());
            return;
        }
        if (McpProtocol.METHOD_PING.equals(method)) {
            respond(id, McpJson.object());
            return;
        }
        if ("sampling/createMessage".equals(method) || "elicitation/create".equals(method)) {
            LOG.warn("MCP server 请求了本客户端未声明支持的能力，已回绝: id={} method={}", config.id(), method);
            registry.noteWarning(config.id(), "server 请求了未声明支持的能力：" + method);
        } else {
            LOG.debug("MCP server 请求了未知方法，已回绝: id={} method={}", config.id(), method);
        }
        respondError(id, McpProtocol.ERROR_METHOD_NOT_FOUND, "本客户端未声明支持 " + method);
    }

    /**
     * 组装 {@code roots/list} 的应答：把进程工作目录作为唯一的根。
     * <p>
     * 这是「几乎零成本但有实际用处」的一项：server 靠它知道自己在哪个项目里工作
     * （例如文件系统类 server 会用它做相对路径的基准），而我们的路径口径本来就是进程工作目录。
     *
     * @return 应答节点
     */
    private static ObjectNode rootsResult() {
        ObjectNode result = McpJson.object();
        com.fasterxml.jackson.databind.node.ArrayNode roots = result.putArray("roots");
        ObjectNode root = roots.addObject();
        root.put("uri", Paths.get("").toAbsolutePath().toUri().toString());
        root.put("name", "working-directory");
        return result;
    }

    /**
     * 拉取工具清单并交给注册方。
     *
     * @param timeoutMillis 单次请求超时毫秒数
     */
    private void refreshTools(long timeoutMillis) {
        List<JsonNode> nodes = listToolNodes(timeoutMillis);
        List<McpToolDefinition> definitions = definitionsOf(nodes);
        toolSink.accept(definitions);
        registry.noteState(config.id(), McpRegistry.State.CONNECTED,
                "已注册 " + definitions.size() + " 个工具");
    }

    /**
     * 分页拉取 {@code tools/list}。
     * <p>
     * 游标必须支持：工具多的 server（浏览器、云平台）会分页返回，只取第一页的表现是
     * 「工具少了一大半，而日志里什么异常都没有」。
     *
     * @param timeoutMillis 单次请求超时毫秒数
     * @return 工具节点列表，保证非 {@code null}
     */
    private List<JsonNode> listToolNodes(long timeoutMillis) {
        List<JsonNode> nodes = new ArrayList<JsonNode>();
        String cursor = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            ObjectNode params = McpJson.object();
            if (cursor != null && !cursor.isEmpty()) {
                params.put("cursor", cursor);
            }
            JsonNode result = request(McpProtocol.METHOD_TOOLS_LIST, params, timeoutMillis,
                    CancellationToken.NONE);
            JsonNode tools = McpJson.childArray(result, "tools");
            if (tools != null) {
                for (JsonNode tool : tools) {
                    nodes.add(tool);
                }
            }
            cursor = McpJson.text(result, "nextCursor", null);
            if (cursor == null || cursor.isEmpty()) {
                break;
            }
        }
        return nodes;
    }

    /**
     * 把协议的工具节点映射成内核的工具定义。
     *
     * @param nodes 工具节点列表
     * @return 不可变定义列表，保证非 {@code null}
     */
    private List<McpToolDefinition> definitionsOf(List<JsonNode> nodes) {
        List<McpToolDefinition> definitions = new ArrayList<McpToolDefinition>();
        Map<String, String> used = new LinkedHashMap<String, String>();
        for (JsonNode node : nodes) {
            if (definitions.size() >= global.maxToolsPerServer()) {
                registry.noteWarning(config.id(), "工具数超过 maxToolsPerServer="
                        + global.maxToolsPerServer() + "，其余未注册");
                break;
            }
            String original = McpJson.text(node, "name", null);
            if (original == null || original.trim().isEmpty()) {
                continue;
            }
            original = original.trim();
            String qualified = McpToolName.qualify(config.id(), original, global.toolPrefix(),
                    global.toolNameMaxLength());
            String clash = used.get(qualified);
            if (clash != null) {
                String disambiguated = disambiguate(qualified, original, used.size());
                registry.noteWarning(config.id(), "工具名 " + qualified + " 已被 " + clash
                        + " 占用，" + original + " 改用 " + disambiguated);
                qualified = disambiguated;
            }
            used.put(qualified, original);
            definitions.add(new McpToolDefinition(config.id(), original, qualified,
                    descriptionOf(node), parametersOf(node), requiredOf(node), isReadOnly(node, original)));
        }
        return Collections.unmodifiableList(definitions);
    }

    /**
     * 取工具描述。
     * <p>
     * 缺描述时退到 {@code title} 再退到原名：模型靠描述判断何时调用，一段空白描述等于一个
     * 永远不会被调用的工具。
     *
     * @param node 工具节点
     * @return 描述
     */
    private static String descriptionOf(JsonNode node) {
        String description = McpJson.text(node, "description", null);
        if (description != null && !description.trim().isEmpty()) {
            return description.trim();
        }
        String title = McpJson.text(node, "title", null);
        return title == null ? "" : title.trim();
    }

    /**
     * 取工具的 inputSchema。
     *
     * @param node 工具节点
     * @return schema 节点；缺失时返回 {@code null}
     */
    private static JsonNode inputSchemaOf(JsonNode node) {
        return McpJson.childObject(node, "inputSchema");
    }

    /**
     * 取参数 Schema 的 properties。
     *
     * @param node 工具节点
     * @return 参数映射，保证非 {@code null}
     */
    private static Map<String, Object> parametersOf(JsonNode node) {
        JsonNode properties = McpJson.childObject(inputSchemaOf(node), "properties");
        if (properties == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            parameters.put(field.getKey(), McpJson.toPlainValue(field.getValue()));
        }
        return parameters;
    }

    /**
     * 取必填参数名。
     *
     * @param node 工具节点
     * @return 不可变列表，保证非 {@code null}
     */
    private static List<String> requiredOf(JsonNode node) {
        return McpJson.stringList(inputSchemaOf(node), "required");
    }

    /**
     * 判定只读。
     * <p>
     * <b>hint 是建议，用户配置是声明，两者取「或」，而缺省是「可写」</b>：
     * {@code readOnlyHint} 由 server 自己填，可能填错；{@code readOnlyTools} 是用户写的。
     * 任一方认为只读就按只读处理——只读的代价只是「少弹一次审批框」，而误判的方向如果是反的
     * （把一个真会改东西的工具当成只读），代价是一个静默的副作用。
     * 因此这里绝不做「没声明就当只读」那种便利推断。
     *
     * @param node     工具节点
     * @param original 原始工具名
     * @return 只读返回 {@code true}
     */
    private boolean isReadOnly(JsonNode node, String original) {
        JsonNode annotations = McpJson.childObject(node, "annotations");
        boolean hinted = McpJson.bool(annotations, "readOnlyHint", false);
        return hinted || config.isDeclaredReadOnly(original);
    }

    /**
     * 为冲突的工具名追加哈希。
     *
     * @param qualified 已冲突的名字
     * @param original  原始工具名
     * @param index     序号，保证同名工具也能各自区分
     * @return 新名字
     */
    private String disambiguate(String qualified, String original, int index) {
        String suffix = "_" + McpToolName.shortHash(original + "@" + index);
        int max = global.toolNameMaxLength();
        String head = qualified.length() + suffix.length() <= max
                ? qualified
                : qualified.substring(0, Math.max(1, max - suffix.length()));
        return head + suffix;
    }

    /**
     * 完成一个在途请求。
     *
     * @param id      应答里的 id 节点
     * @param message 完整消息
     */
    private void completePending(JsonNode id, ObjectNode message) {
        Long key = pendingId(id);
        Pending waiter = key == null ? null : pending.remove(key);
        if (waiter == null) {
            // 超时之后摘掉了等待位：迟到应答按 id 找不到人，计数即可，不做补偿
            registry.noteLateResponse(config.id());
            LOG.debug("丢弃迟到的 MCP 应答: id={} messageId={}", config.id(), id);
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            waiter.fail(describeError(error));
            return;
        }
        waiter.complete(message.get("result"));
    }

    /**
     * 把应答 id 解析成等待位的键。
     *
     * @param id id 节点
     * @return 键；无法解析时返回 {@code null}
     */
    private static Long pendingId(JsonNode id) {
        if (id == null || id.isNull()) {
            return null;
        }
        if (id.isNumber()) {
            return Long.valueOf(id.longValue());
        }
        if (id.isTextual()) {
            try {
                return Long.valueOf(Long.parseLong(id.asText().trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 描述一条 JSON-RPC 错误。
     *
     * @param error 错误节点
     * @return 可读文本
     */
    private static String describeError(JsonNode error) {
        String message = McpJson.text(error, "message", "未提供错误信息");
        int code = McpJson.intOf(error, "code", 0);
        return code == 0 ? message : message + "（code=" + code + "）";
    }

    /**
     * 发起一次请求并等待应答。
     *
     * @param method            方法名
     * @param params            参数，可为 {@code null}
     * @param timeoutMillis     超时毫秒数；{@code <= 0} 表示不超时
     * @param cancellationToken 取消令牌，可为 {@code null}
     * @return 应答里的 result 节点，可能为 {@code null}（server 返回空 result 是合法应答）
     * @throws JellyfishException 连接不可用、写失败、超时、取消或 server 报错时抛出
     */
    private JsonNode request(String method, ObjectNode params, long timeoutMillis,
                             CancellationToken cancellationToken) {
        McpTransport current = transport;
        if (current == null) {
            throw new JellyfishException("MCP server 未连接: " + config.id());
        }
        long id = nextId.incrementAndGet();
        Long key = Long.valueOf(id);
        Pending waiter = new Pending();
        pending.put(key, waiter);
        if (cancellationToken != null) {
            cancellationToken.onCancel(waiter::cancel);
        }
        try {
            current.send(requestMessage(id, method, params));
        } catch (IOException | RuntimeException e) {
            pending.remove(key);
            throw new JellyfishException("写入 MCP server 失败（进程可能已退出）: " + config.id(), e);
        }
        boolean answered = await(waiter, timeoutMillis);
        pending.remove(key);
        if (!answered) {
            throw new JellyfishException("MCP 调用超时（已等待 " + timeoutMillis + " ms）: "
                    + config.id() + " / " + method);
        }
        if (waiter.cancelled) {
            throw new JellyfishException("MCP 调用已取消: " + config.id() + " / " + method);
        }
        if (waiter.error != null) {
            throw new JellyfishException("MCP server 报错: " + config.id() + " / " + method + " - "
                    + waiter.error);
        }
        return waiter.result;
    }

    /**
     * 等待应答。
     *
     * @param waiter        等待位
     * @param timeoutMillis 超时毫秒数；{@code <= 0} 表示不超时（此时只有取消或断连能唤醒）
     * @return 在超时前被唤醒返回 {@code true}
     */
    private static boolean await(Pending waiter, long timeoutMillis) {
        try {
            if (timeoutMillis <= 0) {
                waiter.latch.await();
                return true;
            }
            return waiter.latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 组装请求消息。
     *
     * @param id     请求 id
     * @param method 方法名
     * @param params 参数，可为 {@code null}
     * @return JSON 文本
     */
    private static String requestMessage(long id, String method, ObjectNode params) {
        ObjectNode message = McpJson.object();
        message.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        return McpJson.write(message);
    }

    /**
     * 发送一条通知（无 id）。
     *
     * @param method 方法名
     * @param params 参数，可为 {@code null}
     */
    private void notify(String method, ObjectNode params) {
        McpTransport current = transport;
        if (current == null) {
            return;
        }
        ObjectNode message = McpJson.object();
        message.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        try {
            current.send(McpJson.write(message));
        } catch (IOException | RuntimeException e) {
            LOG.warn("发送 MCP 通知失败: id={} method={}", config.id(), method, e);
        }
    }

    /**
     * 应答一个 server 请求。
     *
     * @param id    请求 id 节点
     * @param result 结果
     */
    private void respond(JsonNode id, ObjectNode result) {
        respondRaw(id, result, null, 0);
    }

    /**
     * 用错误应答一个 server 请求。
     *
     * @param id      请求 id 节点
     * @param code    错误码
     * @param message 错误信息
     */
    private void respondError(JsonNode id, int code, String message) {
        respondRaw(id, null, message, code);
    }

    /**
     * 发送应答。
     *
     * @param id           请求 id 节点
     * @param result       结果，与错误二选一
     * @param errorMessage 错误信息，与结果二选一
     * @param errorCode    错误码
     */
    private void respondRaw(JsonNode id, ObjectNode result, String errorMessage, int errorCode) {
        McpTransport current = transport;
        if (current == null) {
            return;
        }
        ObjectNode message = McpJson.object();
        message.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        message.set("id", id);
        if (errorMessage != null) {
            ObjectNode error = message.putObject("error");
            error.put("code", errorCode);
            error.put("message", errorMessage);
        } else {
            message.set("result", result == null ? McpJson.object() : result);
        }
        try {
            current.send(McpJson.write(message));
        } catch (IOException | RuntimeException e) {
            LOG.warn("应答 MCP server 请求失败: id={}", config.id(), e);
        }
    }

    /**
     * 叫醒全部在途请求。
     *
     * @param reason 失败原因
     */
    private void failAllPending(String reason) {
        for (Map.Entry<Long, Pending> entry : pending.entrySet()) {
            entry.getValue().fail(reason);
        }
    }

    /**
     * 创建通知处理线程池：单线程、守护。
     *
     * @return 线程池
     */
    private static ExecutorService createNotificationWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-notify");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 一个在途请求的等待位。
     * <p>
     * 三种唤醒方式共用一个闩锁：得到应答、得到错误、被取消。{@code cancelled} 与 {@code error}
     * 用 {@code volatile} 字段而不是在 {@code await} 返回后再去查状态机——这里没有状态机，
     * 拿到闩锁之后按字段判断即可。
     */
    private static final class Pending {

        /** 唤醒闩锁。 */
        private final CountDownLatch latch = new CountDownLatch(1);

        /** 应答结果。 */
        private volatile JsonNode result;

        /** 失败原因。 */
        private volatile String error;

        /** 是否被取消。 */
        private volatile boolean cancelled;

        /**
         * 用结果唤醒。
         *
         * @param result 应答结果
         */
        private void complete(JsonNode result) {
            this.result = result;
            latch.countDown();
        }

        /**
         * 用错误唤醒。
         *
         * @param error 失败原因
         */
        private void fail(String error) {
            this.error = error;
            latch.countDown();
        }

        /**
         * 用取消唤醒。
         * <p>
         * <b>这里只能是「置标志 + 唤醒」这类快动作</b>：取消回调可能在界面渲染线程上执行。
         */
        private void cancel() {
            this.cancelled = true;
            latch.countDown();
        }
    }
}
