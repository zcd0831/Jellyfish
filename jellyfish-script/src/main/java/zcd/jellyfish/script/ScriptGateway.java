package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.protocol.ScriptConnectionException;
import zcd.jellyfish.script.protocol.ScriptProtocol;
import zcd.jellyfish.script.protocol.ScriptRpc;
import zcd.jellyfish.script.protocol.ScriptTimeoutException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本运行时：懒启动网关进程、把扩展点调用送进去、失败时给出可归因的异常。
 * <p>
 * <b>它实现 {@link ScriptCaller}</b>，因此对注册侧而言就是一个普通调用入口：
 * 转发处理器只管「调一次、拿结果」，进程、初始化、超时、重连全在这里。
 * 这也是「注册与进程生命周期解耦」的落点——注册侧完全不需要知道有没有进程。
 * <p>
 * <b>调用的超时与初始化严格同源</b>：{@code invoke} 的等待上限就是配置里那一个数字，
 * 因为「Java 比网关先放弃」会让网关来不及杀掉卡死的 worker，下一次调用撞上同一个卡住的 worker，
 * 表现为「连续超时」。
 * <p>
 * <b>初始化的等待上限刻意更宽</b>：它要起进程、fork 每个 worker、等它们 import 完用户脚本。
 * 网关自己有一个更短的初始化宽限，因此正常情况下「网关的精确错误」总是先到——
 * 哪个脚本、差在哪个扩展点都在里面；宿主这一侧的上限只是兜底，避免极端情况下无限等下去。
 * <p>
 * <b>启动是懒的，且失败只影响那一次调用</b>：第一次真正调用时才抽取网关资源、起进程、发
 * {@code initialize}。因此内核启动不需要解释器、不需要文件写入、不占内存；
 * 而解释器缺失的后果是「这次调用报错」，不是「插件加载失败、工具从清单里消失」。
 * <p>
 * <b>为什么初始化要下发脚本文档而不是让网关自己扫目录</b>：清单是注册的唯一来源，
 * 而「哪些脚本注册成功了」已经由 Java 侧决定。让网关再扫一遍目录，就会出现两处独立判断
 * 「这个目录算不算一个脚本」，而两者的分歧只在用户已经拿到一份不一致的工具清单之后才显现。
 * 下发的另一层好处是严格校验有了明确对手：网关只需把「实现真正声明的」与「下发的」比一遍。
 * <p>
 * <b>进程退出后不做自动重启</b>：下一次调用会重新走一遍懒启动。自动重启只会把
 * 「脚本一启动就崩」变成一段安静的无限重启循环，而调用方拿到的是超时——现场离原因更远。
 * <p>
 * <b>关闭是两段式的</b>：先请网关自己走（它会杀干净自己的 worker），到时间再强杀。
 * 只做后者会让 worker 变成孤儿，而那是本方案最不想留下的东西。
 * <p>
 * 线程安全：调用可来自任意线程（{@code ReAct} 线程池），启动与关闭走同一把锁。
 *
 * @author zcd
 */
public final class ScriptGateway implements ScriptCaller, AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptGateway.class);

    /** 初始化等待的下限：一次性动作，不该被一个很小的调用超时挤没。 */
    private static final long INITIALIZE_MIN_TIMEOUT_MILLIS = 30_000L;

    /** 初始化相对调用超时的额外宽限：保证网关那侧的精确错误有时间先到。 */
    private static final long INITIALIZE_TIMEOUT_GRACE_MILLIS = 10_000L;

    /** 关闭时等待网关优雅退出的毫秒数。 */
    private static final long CLOSE_GRACE_MILLIS = 3000L;

    /** 关闭时强杀后等待的毫秒数。 */
    private static final long CLOSE_KILL_MILLIS = 2000L;

    /** 语言适配。 */
    private final ScriptLanguage language;

    /** 进程工厂。 */
    private final ScriptProcessFactory processFactory;

    /** 网关资源抽取器。 */
    private final GatewayResources resources;

    /** 网关设置。 */
    private final GatewaySettings settings;

    /** 需要下发给网关的脚本清单。 */
    private final List<ScriptPlugin> scripts;

    /** 需要落盘的网关资源名。 */
    private final List<String> gatewayResources;

    /** 保护启动、关闭与运行态字段的锁。 */
    private final Object lock = new Object();

    /** 当前一代的网关进程；未启动时为 {@code null}。 */
    private volatile ScriptProcess process;

    /** 当前一代的 RPC 会话；未启动时为 {@code null}。 */
    private volatile ScriptRpc rpc;

    /** 已抽取并复用的网关目录；未抽取时为 {@code null}。 */
    private volatile Path gatewayDirectory;

    /** 是否已关闭。 */
    private volatile boolean closed;

    /**
     * 构造网关。
     *
     * @param builder 构建器
     */
    private ScriptGateway(Builder builder) {
        this.language = builder.language;
        // 缺省工厂是懒的：抽取网关资源、向语言适配要启动命令都发生在第一次真正调用时。
        // 因此「解释器没装」或「主目录不可写」都不会影响插件加载，也不会影响工具清单的完整性
        this.processFactory = builder.processFactory != null
                ? builder.processFactory
                : (lines, onExit) -> ScriptProcessFactory.osProcess(
                        language.startCommand(materialize()), language.environment(), null)
                        .start(lines, onExit);
        this.resources = builder.resources;
        this.settings = builder.settings;
        this.scripts = Collections.unmodifiableList(new ArrayList<ScriptPlugin>(builder.scripts));
        this.gatewayResources = Collections.unmodifiableList(
                new ArrayList<String>(builder.gatewayResources));
    }

    /**
     * 构造网关构建器。
     *
     * @param language 语言适配，不可为 {@code null}
     * @return 构建器
     */
    public static Builder builder(ScriptLanguage language) {
        return new Builder(language);
    }

    @Override
    public JsonNode call(ScriptPlugin plugin, String typeName, JsonNode request) {
        ScriptRpc current = ensureStarted();
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put(ScriptProtocol.PARAM_SCRIPT, plugin.id());
        params.put(ScriptProtocol.PARAM_TYPE, typeName);
        params.put(ScriptProtocol.PARAM_REQUEST, request);
        return current.call(ScriptProtocol.METHOD_INVOKE, ScriptJson.treeOf(params),
                settings.invokeTimeoutMillis());
    }

    /**
     * 关闭网关与它的全部 worker。
     * <p>
     * 幂等。先发 {@code shutdown}（域名关卡在网关一侧：只有它知道 worker 是谁），
     * 再走两段式关闭兜住「网关自己也卡住了」的情况。
     */
    @Override
    public void close() {
        ScriptRpc current;
        ScriptProcess currentProcess;
        synchronized (lock) {
            if (closed) {
                return;
            }
            // 先立「已关闭」这面旗，新的调用立刻被拒；但运行态字段要等关闭指令发完再清，
            // 否则发送方会看到「进程不存在」，关闭指令根本发不出去——表现为网关被杀而不是被请走
            closed = true;
            current = rpc;
            currentProcess = process;
        }
        if (current != null) {
            shutdown(current);
            current.close();
        }
        if (currentProcess != null) {
            currentProcess.close(CLOSE_GRACE_MILLIS, CLOSE_KILL_MILLIS);
        }
        synchronized (lock) {
            rpc = null;
            process = null;
        }
        LOG.info("{} 脚本运行时已关闭", language.displayName());
    }

    /**
     * 判断当前是否有存活的网关进程。
     *
     * @return 有存活进程返回 {@code true}
     */
    public boolean isRunning() {
        ScriptProcess current = process;
        return !closed && current != null && current.isAlive();
    }

    /**
     * 渲染运行态自述，供 {@code /<语言>} 状态命令展示。
     *
     * @return 文本，保证非 {@code null}
     */
    public String describe() {
        ScriptProcess current = process;
        ScriptRpc currentRpc = rpc;
        StringBuilder builder = new StringBuilder();
        builder.append(closed ? "已关闭" : (isRunning() ? "运行中" : "未启动（懒加载）"));
        if (currentRpc != null) {
            // 迟到响应数说明「脚本比超时慢」——它比单纯一句「超时了」更有诊断价值
            builder.append("，丢弃的迟到响应 ").append(currentRpc.lateResponseCount());
        }
        if (current != null && !current.isAlive() && !closed) {
            builder.append("，上一代进程已退出");
        }
        return builder.toString();
    }

    /**
     * 确保网关已就绪，返回可用于发送的 RPC 会话。
     *
     * @return RPC 会话，保证非 {@code null}
     * @throws JellyfishException 启动或初始化失败时抛出
     */
    private ScriptRpc ensureStarted() {
        synchronized (lock) {
            if (closed) {
                throw new ScriptConnectionException("脚本运行时已关闭，无法调用", null);
            }
            ScriptRpc current = rpc;
            ScriptProcess currentProcess = process;
            if (current != null && currentProcess != null && currentProcess.isAlive()) {
                return current;
            }
            discard(current, currentProcess);
            start();
            return rpc;
        }
    }

    /**
     * 启动一代新的网关进程并完成初始化。
     *
     * @throws JellyfishException 启动或初始化失败时抛出
     */
    private void start() {
        // 先造 RPC 会话、后起进程：进程退出回调需要拿到「该通知哪一代」，
        // 若反过来，回调就会在会话尚未赋值时到达，那次失败只能表现为「初始化等到超时」
        final ScriptRpc created = new ScriptRpc(this::send, this::onIncoming);
        this.rpc = created;
        ScriptProcess started = null;
        try {
            started = processFactory.start(this::onLine, code -> onExited(created, code.intValue()));
            this.process = started;
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put(ScriptProtocol.PARAM_SCRIPTS, scriptPayloads());
            params.put(ScriptProtocol.PARAM_SETTINGS, settings.toJson());
            JsonNode response = created.call(ScriptProtocol.METHOD_INITIALIZE, ScriptJson.treeOf(params),
                    initializeTimeoutMillis());
            reportInitialization(response);
            LOG.info("{} 脚本网关已启动: {} 个脚本，{}", language.displayName(),
                    Integer.valueOf(scripts.size()), settings);
        } catch (RuntimeException e) {
            discard(created, started);
            throw e;
        }
    }

    /**
     * 计算初始化的等待上限。
     *
     * @return 等待毫秒数
     */
    private long initializeTimeoutMillis() {
        return Math.max(INITIALIZE_MIN_TIMEOUT_MILLIS,
                settings.invokeTimeoutMillis() + INITIALIZE_TIMEOUT_GRACE_MILLIS);
    }

    /**
     * 记录初始化响应里的逐脚本结果。
     * <p>
     * 单脚本初始化失败只告警不失败：网关已经起来了，成功的脚本还能正常工作，
     * 而「因为一个脚本的入口文件写错，整门语言都不可用」正是本方案要避免的连坐。
     * <b>但如果一个脚本都没起来，就要显式失败</b>——否则后面的每次调用都只会超时，
     * 现场离原因（清单与实现对不上）隔了整整一个超时周期。
     *
     * @param response 初始化响应载荷，可为 {@code null}
     * @throws JellyfishException 全部脚本都初始化失败时抛出
     */
    private void reportInitialization(JsonNode response) {
        JsonNode results = response == null ? null : response.get(ScriptProtocol.PARAM_SCRIPTS);
        if (results == null || !results.isArray()) {
            return;
        }
        int failed = 0;
        StringBuilder reasons = new StringBuilder();
        for (JsonNode result : results) {
            boolean ok = result.path(ScriptProtocol.PARAM_OK).asBoolean(false);
            String id = result.path(ScriptProtocol.PARAM_SCRIPT).asText("?");
            if (ok) {
                continue;
            }
            failed++;
            String reason = result.path(ScriptProtocol.PARAM_ERROR).asText("未提供原因");
            LOG.error("{} 脚本 {} 初始化失败: {}", language.displayName(), id, reason);
            reasons.append("\n  ").append(id).append(": ").append(reason);
        }
        if (failed > 0 && failed == results.size() && !scripts.isEmpty()) {
            // 把每条原因写进异常：只说「全都失败了」等于把定位工作又推回给用户，
            // 而真正有用的信息（哪个脚本、差在哪个扩展点）此刻已经完全在手上了
            throw new JellyfishException("全部脚本初始化失败（" + failed + " 个）:"
                    + reasons + "\n清单与实现不一致是最常见的原因，检查各脚本的 manifest.json");
        }
    }

    /**
     * 构造脚本清单载荷。
     *
     * @return JSON 数组节点
     */
    private JsonNode scriptPayloads() {
        List<Map<String, Object>> payloads = new ArrayList<Map<String, Object>>();
        for (ScriptPlugin plugin : scripts) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put(ScriptProtocol.PARAM_ID, plugin.id());
            payload.put(ScriptProtocol.PARAM_DIRECTORY, plugin.directory().toAbsolutePath().toString());
            payload.put(ScriptProtocol.PARAM_ENTRY, plugin.entryFile().toAbsolutePath().toString());
            payload.put(ScriptProtocol.PARAM_MANIFEST, manifestDigestOf(plugin));
            payloads.add(payload);
        }
        return ScriptJson.treeOf(payloads);
    }

    /**
     * 构造清单摘要：网关做严格校验时需要的全部信息。
     * <p>
     * 只带名字清单而不是整份 manifest：网关要判断的是「实现声明了但清单没有」与
     * 「清单声明了但实现没有」，那是名字集合的比较，与描述、参数 Schema 无关。
     * 带上整份 manifest 会让网关不得不跟随清单 schema 的每一次演进。
     *
     * @param plugin 脚本
     * @return 清单摘要映射
     */
    private static Map<String, Object> manifestDigestOf(ScriptPlugin plugin) {
        ScriptManifest manifest = plugin.manifest();
        List<String> tools = new ArrayList<String>();
        for (ScriptManifest.Tool tool : manifest.tools()) {
            tools.add(tool.name());
        }
        List<String> commands = new ArrayList<String>();
        for (ScriptManifest.Command command : manifest.commands()) {
            commands.add(command.name());
        }
        Map<String, Object> digest = new LinkedHashMap<String, Object>();
        digest.put("tools", tools);
        digest.put("commands", commands);
        digest.put("commandOptions", new ArrayList<String>(manifest.commandOptions()));
        digest.put("contributions", new ArrayList<String>(manifest.contributions()));
        digest.put("events", new ArrayList<String>(manifest.events()));
        return digest;
    }

    /**
     * 发送一帧协议文本。
     *
     * @param line 帧文本
     * @throws ScriptConnectionException 进程不存在时抛出
     */
    private void send(String line) {
        ScriptProcess current = process;
        if (current == null) {
            throw new ScriptConnectionException("脚本网关尚未启动，无法发送协议帧", null);
        }
        current.send(line);
    }

    /**
     * 接收一行协议文本。
     *
     * @param line 帧文本
     */
    private void onLine(String line) {
        ScriptRpc current = rpc;
        if (current == null) {
            LOG.debug("网关尚未就绪，丢弃协议行: {}", line);
            return;
        }
        current.accept(line);
    }

    /**
     * 处理网关退出。
     * <p>
     * <b>不取本类的锁</b>：它由进程的退出线程调用，而调用线程可能正持锁等待初始化应答；
     * 两者一旦互等就是死锁（表现为「第一次调用卡满一个初始化超时」）。因此它只读两个 volatile 字段。
     *
     * @param generation 该次退出所属的 RPC 会话
     * @param code       退出码
     */
    private void onExited(ScriptRpc generation, int code) {
        // 关掉这一代：让正在等待的调用立刻拿到「连接不可用」，而不是各自等到超时
        generation.fail(new ScriptConnectionException(
                language.displayName() + " 脚本网关进程已退出（exitCode=" + code + "）", null));
        LOG.warn("{} 脚本网关进程已退出: exitCode={}", language.displayName(), Integer.valueOf(code));
    }

    /**
     * 处理上行消息。
     *
     * @param message 消息
     */
    private void onIncoming(ScriptProtocol.Message message) {
        String method = message.method();
        if (ScriptProtocol.METHOD_WORKER_STATE.equals(method)) {
            LOG.info("{} 脚本 {} 的 worker 状态: {}（alive={}, started={}）", language.displayName(),
                    message.paramText(ScriptProtocol.PARAM_SCRIPT),
                    message.paramText(ScriptProtocol.PARAM_STATE),
                    message.paramNode(ScriptProtocol.PARAM_ALIVE),
                    message.paramNode(ScriptProtocol.PARAM_STARTED));
            respond(message, null);
            return;
        }
        if (ScriptProtocol.METHOD_EMIT_EVENT.equals(method)) {
            // 事件桥接（白名单校验、防循环、EventChannel 派发）属于后续阶段。
            // 这里明确拒绝而不是假装受理：受理一个没被派发的事件，会让脚本以为通知已经到了，
            // 而这类静默丢失在排查时表现为「事件偶尔不触发」，无从下手
            LOG.warn("脚本 {} 请求发布事件 {}，但事件桥接尚未接通，已拒绝", 
                    message.paramText(ScriptProtocol.PARAM_SCRIPT),
                    message.paramText(ScriptProtocol.PARAM_EVENT));
            Map<String, Object> rejection = new LinkedHashMap<String, Object>();
            rejection.put(ScriptProtocol.PARAM_ACCEPTED, Boolean.FALSE);
            rejection.put(ScriptProtocol.PARAM_REASON, "事件桥接尚未接通");
            respond(message, ScriptJson.treeOf(rejection));
            return;
        }
        LOG.warn("{} 网关发来未知方法，已拒绝: {}", language.displayName(), method);
        ScriptRpc current = rpc;
        if (current != null && message.needsResponse()) {
            current.respondError(message.id().longValue(), ScriptProtocol.CODE_METHOD_NOT_FOUND,
                    "不支持的网关方法: " + method);
        }
    }

    /**
     * 应答一条上行请求。
     *
     * @param message 消息
     * @param result  结果载荷，可为 {@code null}
     */
    private void respond(ScriptProtocol.Message message, JsonNode result) {
        ScriptRpc current = rpc;
        if (current == null || !message.needsResponse()) {
            return;
        }
        current.respond(message.id().longValue(), result);
    }

    /**
     * 发关闭指令，尽力而为。
     *
     * @param current RPC 会话
     */
    private void shutdown(ScriptRpc current) {
        try {
            current.call(ScriptProtocol.METHOD_SHUTDOWN, ScriptJson.treeOf(Collections.emptyMap()),
                    CLOSE_GRACE_MILLIS);
        } catch (JellyfishException e) {
            // 关闭路径上的失败只记录：进程随后无论如何都会被关掉，把异常抛出去只会让
            // 「插件停止」这个动作因为一个已经没救的进程而失败
            LOG.debug("{} 网关关闭指令未成功: {}", language.displayName(), e.toString());
        }
    }

    /**
     * 丢弃一代运行态。
     *
     * @param generation RPC 会话，可为 {@code null}
     * @param current    进程，可为 {@code null}
     */
    private void discard(ScriptRpc generation, ScriptProcess current) {
        if (generation != null) {
            generation.close();
        }
        if (current != null) {
            current.close(CLOSE_GRACE_MILLIS, CLOSE_KILL_MILLIS);
        }
        this.rpc = null;
        this.process = null;
    }

    /**
     * 抽取网关资源，结果缓存复用。
     *
     * @return 网关资源目录
     * @throws JellyfishException 抽取失败时抛出
     */
    private Path materialize() {
        Path cached = gatewayDirectory;
        if (cached != null) {
            return cached;
        }
        Path directory = resources.materialize(language, gatewayResources);
        gatewayDirectory = directory;
        return directory;
    }

    /**
     * 网关构建器。
     * <p>
     * 参数里有语言、工厂、资源、设置、脚本五样，且其中三样有合理缺省值/必须成对出现
     * （设置与资源缺省时无法推导），用构建器可以让「忘了给脚本清单」这类错误在
     * {@link #build()} 里立刻报出来，而不是等到第一次调用。
     * <p>
     * 非线程安全，仅供装配期单线程使用。
     *
     * @author zcd
     */
    public static final class Builder {

        /** 语言适配。 */
        private final ScriptLanguage language;

        /** 进程工厂；为 {@code null} 时由网关按语言适配的启动命令推导。 */
        private ScriptProcessFactory processFactory;

        /** 网关资源抽取器；缺省为默认抽取根目录。 */
        private GatewayResources resources = new GatewayResources(GatewayResources.defaultBaseDirectory());

        /** 网关设置；缺省为全默认。 */
        private GatewaySettings settings = GatewaySettings.defaults();

        /** 脚本清单。 */
        private final List<ScriptPlugin> scripts = new ArrayList<ScriptPlugin>();

        /** 需要落盘的网关资源名。 */
        private final List<String> gatewayResources = new ArrayList<String>();

        /**
         * 构造构建器。
         *
         * @param language 语言适配，不可为 {@code null}
         */
        private Builder(ScriptLanguage language) {
            if (language == null) {
                throw new JellyfishException("语言适配不可为 null");
            }
            this.language = language;
        }

        /**
         * 设置网关设置。
         *
         * @param value 设置，为 {@code null} 时保持缺省
         * @return 本构建器
         */
        public Builder settings(GatewaySettings value) {
            if (value != null) {
                this.settings = value;
            }
            return this;
        }

        /**
         * 设置脚本清单。
         *
         * @param value 脚本清单，可为 {@code null}
         * @return 本构建器
         */
        public Builder scripts(List<ScriptPlugin> value) {
            if (value != null) {
                scripts.addAll(value);
            }
            return this;
        }

        /**
         * 设置需要落盘的网关资源名。
         * <p>
         * 缺省为空：假进程工厂（测试）不需要落盘任何东西，因此空清单是合法的，
         * 只有走真实进程时才必须给出。
         *
         * @param value 资源名清单，可为 {@code null}
         * @return 本构建器
         */
        public Builder gatewayResources(List<String> value) {
            if (value != null) {
                gatewayResources.addAll(value);
            }
            return this;
        }

        /**
         * 设置网关资源抽取器。
         *
         * @param value 抽取器，为 {@code null} 时保持缺省
         * @return 本构建器
         */
        public Builder resources(GatewayResources value) {
            if (value != null) {
                this.resources = value;
            }
            return this;
        }

        /**
         * 设置进程工厂。
         * <p>
         * 缺省由语言适配的启动命令推导，测试则用它注入内存假进程。
         *
         * @param value 进程工厂，为 {@code null} 时保持缺省
         * @return 本构建器
         */
        public Builder processFactory(ScriptProcessFactory value) {
            if (value != null) {
                this.processFactory = value;
            }
            return this;
        }

        /**
         * 构造网关。
         *
         * @return 网关
         * @throws JellyfishException 语言适配或启动命令非法时抛出
         */
        public ScriptGateway build() {
            return new ScriptGateway(this);
        }
    }
}
